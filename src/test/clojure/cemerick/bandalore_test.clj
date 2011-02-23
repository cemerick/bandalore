(ns cemerick.bandalore-test
  (:use cemerick.bandalore
    clojure.test
    clojure.contrib.core)
  (:refer-clojure :exclude (send)))

#_(do
    (System/setProperty "aws.id" "")
    (System/setProperty "aws.secret-key" ""))

; kill the verbose aws logging
(.setLevel (java.util.logging.Logger/getLogger "com.amazonaws")
  java.util.logging.Level/WARNING)

(def client
  (let [id (System/getProperty "aws.id")
        secret-key (System/getProperty "aws.secret-key")]
    (assert (and id secret-key))
    (create-client id secret-key)))

(def *test-queue-url* nil)

(defn- uuid
  []
  (str (java.util.UUID/randomUUID)))

(def test-queue-name-prefix "bandalore-test-")

(defn- test-queue-name
  []
  (str test-queue-name-prefix (uuid)))

(defn- verify-queue-cleanup
  [f]
  (f)
  (doseq [q (filter #(.contains % test-queue-name-prefix) (list-queues client))]
    (delete-queue client q)))

(use-fixtures :once verify-queue-cleanup)

(defn- wait-for-condition
  [f desc]
  (let [wait-condition (loop [waiting 0]
                         (cond
                           (f) true
                           (>= waiting 120) false
                           :else (do
                                   (Thread/sleep 1000)
                                   (recur (inc waiting)))))]
    (when-not (is wait-condition desc)
      (throw (IllegalStateException. desc)))
    wait-condition))

(defmacro defsqstest
  [name & body]
  `(deftest ~name
     (println '~name) ; lots of sleeping in these tests, give some indication of life
     (binding [*test-queue-url* (create-queue client (test-queue-name))]
       (try
         (is *test-queue-url*)
         ~@body
         (finally
           (delete-queue client *test-queue-url*))))))

(defsqstest test-list-queues
  (let [msg (uuid)]
    ; sending a msg seems to "force" the queue's existence in listings
    (send client *test-queue-url* msg)
    (wait-for-condition #((set (list-queues client)) *test-queue-url*)
      "Created queue not visible in result of list-queues")))

(defsqstest test-queue-attrs
  (let [{:strs [VisibilityTimeout MaximumMessageSize] :as base-attrs} (queue-attrs client *test-queue-url*)
        expected {"VisibilityTimeout" "117" "MaximumMessageSize" "1535"}]
    (is (and VisibilityTimeout MaximumMessageSize))
    (queue-attrs client *test-queue-url* expected)
    (wait-for-condition #(= expected
                           (->> (queue-attrs client *test-queue-url*)
                             (filter (comp (set (keys expected)) first))
                             (into {})))
      "Queue attribute test failed after waiting for test condition")))

(defn- wait-for-full-queue
  [q min-cnt queue-name]
  (wait-for-condition #(-> (queue-attrs client q)
                         (get "ApproximateNumberOfMessages")
                         Integer/parseInt
                         (> min-cnt))
    (str queue-name " queue never filled up")))

(defsqstest test-polling-receive
  (let [data (range 100)
        q *test-queue-url*]
    ; we want to be testing polling-receive's ability to wait when there's nothing available
    (future (doseq [x data]
              (send client q (str x))))
    (wait-for-full-queue *test-queue-url* 50 "polling-receive")
    (is (== (apply + data)
          (->>
            (polling-receive client *test-queue-url* :max-wait 10000)
            (map (deleting-consumer client (comp read-string :body)))
            (apply +))))))

(defsqstest receive-limit+attrs
  (doseq [x (range 100)]
    (send client *test-queue-url* (str x)))
  (wait-for-full-queue *test-queue-url* 50 "limit+attrs")
  (let [[{:keys [attributes]} :as msgs] (receive client *test-queue-url*)]
    (is (== 1 (count msgs)))
    (is (empty? attributes)))
  (wait-for-condition #(let [msgs (receive client *test-queue-url* :limit 10 :attributes #{"All"})]
                         (is (->> msgs
                               (map (comp count :attributes))
                               (every? pos?)))
                         (< 1 (count msgs)))
    "limit+attrs queue never produced > 1 message on a receive"))

(defsqstest receive-visibility
  (doseq [x (range 10)]
    (send client *test-queue-url* (str x)))
  (wait-for-full-queue *test-queue-url* 5 "recieve-visibility")
  (let [v (-> (receive client *test-queue-url* :visibility 5) first :body read-string)]
    (is (some #(= v (-> % :body read-string)) (polling-receive client *test-queue-url* :max-wait 10000)))))

