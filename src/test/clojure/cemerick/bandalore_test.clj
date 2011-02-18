(ns cemerick.bandalore-test
  (:use cemerick.bandalore
    clojure.test
    clojure.contrib.core)
  (:refer-clojure :exclude (send)))

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

(defn- test-queue-name
  []
  (str "bandalore-test-" (uuid)))

(defmacro defqueuetest
  [name & body]
  `(deftest ~name
     (binding [*test-queue-url* (create-queue client (test-queue-name))]
       (try
         (is *test-queue-url*)
         ~@body
         (finally
           (delete-queue client *test-queue-url*))))))

(defqueuetest listing-queues
  (let [msg (uuid)]
    (send client *test-queue-url* msg)
    (is ((set (list-queues client)) *test-queue-url*))))

