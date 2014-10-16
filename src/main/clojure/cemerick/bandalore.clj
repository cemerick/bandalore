;   Copyright (c) Chas Emerick. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cemerick.bandalore
  (:import com.amazonaws.services.sqs.AmazonSQSClient
    (com.amazonaws.services.sqs.model
      AddPermissionRequest ChangeMessageVisibilityRequest CreateQueueRequest
      DeleteMessageRequest DeleteQueueRequest GetQueueAttributesRequest
      GetQueueUrlRequest ListQueuesRequest Message ReceiveMessageRequest
      ReceiveMessageResult RemovePermissionRequest SendMessageRequest
      SendMessageResult SetQueueAttributesRequest))
  (:refer-clojure :exclude (send)))

(defn create-client
  "Creates a synchronous AmazonSQSClient using the provided account id, secret key,
   and optional com.amazonaws.ClientConfiguration."
  ([]
    (create-client (com.amazonaws.ClientConfiguration.)))
  ([client-config]
    (AmazonSQSClient.
      (.withUserAgent client-config "Bandalore - SQS for Clojure")))
  ([id secret-key]
    (create-client id secret-key (com.amazonaws.ClientConfiguration.)))
  ([id secret-key client-config]
    (AmazonSQSClient. (com.amazonaws.auth.BasicAWSCredentials. id secret-key)
      (.withUserAgent client-config "Bandalore - SQS for Clojure"))))

(def ^{:private true} visibility-warned? (atom false))

(defn create-queue
  "Creates a queue with the given name, returning the corresponding URL string.
   Returns successfully if the queue already exists."
  [^AmazonSQSClient client queue-name & {:as options}]
  (when (and (:visibility options) (not @visibility-warned?))
    (println "[WARNING] :visibility option to cemerick.bandalore/create-queue no longer supported;")
    (println "[WARNING] See https://github.com/cemerick/bandalore/issues/3")
    (reset! visibility-warned? true))
  (->> (CreateQueueRequest. queue-name)
    (.createQueue client)
    .getQueueUrl))

(defn delete-queue
  "Deletes the queue specified by the given URL string."
  [^AmazonSQSClient client queue-url]
  (.deleteQueue client (DeleteQueueRequest. queue-url)))

(defn list-queues
  "Returns a seq of all queues' URL strings.  Takes an optional string prefix
  argument to only list queues with names that start with the prefix."
  [^AmazonSQSClient client & {:keys [prefix]}]
  (->> (ListQueuesRequest. prefix)
    (.listQueues client)
    .getQueueUrls
    seq))

(defn queue-url
  "Returns the URL for a named queue"
  [^AmazonSQSClient client queue-name]
   (.getQueueUrl (GetQueueUrlRequest. queue-name)))

(defn queue-attrs
  "Gets or sets the attributes of a queue specified by its URL string.
   
   When setting attributes on a queue, the attribute map must have String keys
   and values.  Note that the SQS API only supports setting one queue attribute
   per request, so each attribute name/value pair in the provided `attr-map`
   will provoke a separate API request."
  ([^AmazonSQSClient client queue-url]
    (->>
      (.withAttributeNames (GetQueueAttributesRequest. queue-url) #{"All"})
      (.getQueueAttributes client)
      .getAttributes
      (into {})))
  ([^AmazonSQSClient client queue-url attr-map]
    (doseq [[k v] attr-map]
      (.setQueueAttributes client
        (SetQueueAttributesRequest. queue-url {(str k) (str v)})))))
         
(defn send
  "Sends a new message with the given string body to the queue specified
   by the string URL.  Returns a map with :id and :body-md5 slots."
  [^AmazonSQSClient client queue-url message]
  (let [resp (.sendMessage client (SendMessageRequest. queue-url message))]
    {:id (.getMessageId resp)
     :body-md5 (.getMD5OfMessageBody resp)}))

(defn- message-map
  [queue-url ^Message msg]
  {:attributes (.getAttributes msg)
   :body (.getBody msg)
   :body-md5 (.getMD5OfBody msg)
   :id (.getMessageId msg)
   :receipt-handle (.getReceiptHandle msg)
   :source-queue queue-url})
 
(defn receive
  "Receives one or more messages from the queue specified by the given URL.
   Optionally accepts keyword arguments:

   :limit - between 1 (default) and 10, the maximum number of messages to receive
   :visibility - seconds the received messages should not be delivered to other
             receivers; defaults to the queue's visibility attribute
   :attributes - a collection of string names of :attributes to include in
             received messages; e.g. #{\"All\"} will include all attributes,
             #{\"SentTimestamp\"} will include only the SentTimestamp attribute, etc.
             Defaults to the empty set (i.e. no attributes will be included in
             received messages).
             See the SQS documentation for all support message attributes.
   :wait-time-seconds - enables long poll support. time is in seconds, bewteen
             0 (default - no long polling) and 20.
             Allows Amazon SQS service to wait until a message is available
             in the queue before sending a response.
             See the SQS documentation at (http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-long-polling.html)

   Returns a seq of maps with these slots:
   
   :attributes - message attributes
   :body - the string body of the message
   :body-md5 - the MD5 checksum of :body
   :id - the message's ID
   :receipt-handle - the ID used to delete the message from the queue after
             it has been fully processed.
   :source-queue - the URL of the queue from which the message was received"
  [^AmazonSQSClient client queue-url & {:keys [limit
                                               visibility
                                               wait-time-seconds
                                               ^java.util.Collection attributes]
                                        :or {limit 1
                                             attributes #{}}}]
  (let [req (-> (ReceiveMessageRequest. queue-url)
              (.withMaxNumberOfMessages (-> limit (min 10) (max 1) int Integer/valueOf))
              (.withAttributeNames attributes))
        req (if wait-time-seconds (.withWaitTimeSeconds req (Integer/valueOf (int wait-time-seconds))) req)
        req (if visibility (.withVisibilityTimeout req (Integer/valueOf (int visibility))) req)]
    (->> (.receiveMessage client req)
      .getMessages
      (map (partial message-map queue-url)))))

(defn polling-receive
  "Receives one or more messages from the queue specified by the given URL.
   Returns a lazy seq of messages from multiple receive calls, performed
   as the seq is consumed.

   Accepts the same keyword arguments as `receive`, in addition to:

   :period - time in ms to wait after an unsuccessful `receive` request (default: 500)
   :max-wait - maximum time in ms to wait to successfully receive messages before
           terminating the lazy seq (default 5000)"
  [client queue-url & {:keys [period max-wait]
                       :or {period 500
                            max-wait 5000}
                       :as receive-opts}]
  (let [waiting (atom 0)
        receive-opts (mapcat identity receive-opts)
        message-seq (fn message-seq []
                      (lazy-seq
                        (if-let [msgs (seq (apply receive client queue-url receive-opts))]
                          (do
                            (reset! waiting 0)
                            (concat msgs (message-seq)))
                          (do
                            (when (<= (swap! waiting + period) max-wait)
                              (Thread/sleep period)
                              (message-seq))))))]
    (message-seq)))

(defn- receipt-handle
  [msg-ish]
  (cond
    (string? msg-ish) msg-ish
    (map? msg-ish) (:receipt-handle msg-ish)
    :else (.getReceiptHandle ^Message msg-ish)))

(defn delete
  "Deletes a message from the queue from which it was received.
   
   The message must be a message map provided by `receive` or `polling-receive`
   if the `queue-url` is not provided explicitly.  Otherwise,
   `message` may be a message map, Message object,
   or the :receipt-handle value from a received message map."
  ([^AmazonSQSClient client message]
    (delete client (:source-queue message) message))
  ([^AmazonSQSClient client queue-url message]
    (->> message
      receipt-handle
      (DeleteMessageRequest. queue-url)
      (.deleteMessage client))))

(defn deleting-consumer
  "Some minor middleware for simplifying the deletion of processed messages
   from their originating queue.  Given a client and a function `f`, returns
   a function that accepts a single message (as provided by `receive` and `polling-receive`);
   it calls `f` with that message, and then deletes the message from its
   originating queue. `f`'s return value is passed along."
  [client f]
  (fn [message]
    (let [ret (f message)]
      (delete client message)
      ret)))

(defn change-message-visibility
  "Updates the visibility timeout for a single message in the queue identified by the
   given URL.

   `message` may be a message map, Message object,
   or the :receipt-handle value from a received message map."
  [^AmazonSQSClient client queue-url message visibility-timeout]
  (.changeMessageVisibility client
    (ChangeMessageVisibilityRequest.
      queue-url (receipt-handle message) visibility-timeout)))
           

;; ResponseMetadata 	getCachedResponseMetadata(AmazonWebServiceRequest request)
      ;;    Returns additional metadata for a previously executed successful, request, typically used for debugging issues where a service isn't acting as expected.
;; void 	removePermission(RemovePermissionRequest removePermissionRequest)
;           The RemovePermission action revokes any permissions in the queue policy that matches the specified Label parameter.
;; void 	addPermission(AddPermissionRequest addPermissionRequest)
           ;;The AddPermission action adds a permission to a queue for a specific principal.
