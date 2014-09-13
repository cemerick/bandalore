# Bandalore

[Bandalore](http://github.com/cemerick/bandalore) is a Clojure client
library for Amazon's [Simple Queue Service](http://aws.amazon.com/sqs/).  It depends upon
the standard [AWS SDK for Java](http://aws.amazon.com/sdkforjava/),
and provides a Clojure-idiomatic API for the SQS-related functionality
therein.

## "Installation"

Bandalore is available in Maven central.  Add it to your Maven project's `pom.xml`:

```xml
<dependency>
  <groupId>com.cemerick</groupId>
  <artifactId>bandalore</artifactId>
  <version>0.0.6</version>
</dependency>
```

or your leiningen project.clj:

```clojure
[com.cemerick/bandalore "0.0.6"]
```

Bandalore is compatible with Clojure 1.2.0+.

## Logging

I strongly recommend squelching the AWS SDK's very verbose logging
before using Bandalore (the former spews a variety of stuff out on
INFO that I personally think should be in DEBUG or TRACE).  You can
do this with this snippet:

```clojure
(.setLevel (java.util.logging.Logger/getLogger "com.amazonaws")
  java.util.logging.Level/WARNING)
```

Translate as necessary if you're using log4j, etc.

## Usage

You should be familiar with [SQS itself](http://aws.amazon.com/sqs/)
before sensibly using this library.  That said, Bandalore's API
is well-documented.

You'll first need to load the library and create a SQS client object
to do anything:

```clojure
(require '[cemerick.bandalore :as sqs])
(def client (sqs/create-client "your aws id" "your aws secret-key"))
```

**Security Note** If your application using Bandalore is deployed to EC2, _you
should not put your AWS credentials on those EC2 nodes_.  Rather,
  [give your EC2 instances IAM roles](http://docs.aws.amazon.com/AWSSdkDocsJava/latest/DeveloperGuide/java-dg-roles.html),
  and use the nullary arity of `create-client`:
  
```clojure
(require '[cemerick.bandalore :as sqs])
(def client (sqs/create-client))
```

This will use credentials assigned to your EC2 node based on its
role that are automatically rotated.

You can create, delete, and list queues:

```clojure
#> (sqs/create-queue client "foo")
"https://queue.amazonaws.com/499312652346/foo"
#> (sqs/list-queues client)
("https://queue.amazonaws.com/499312652346/foo")
#> (sqs/delete-queue client (first *1))
nil
#> (list-queues client)
nil
```

*Note that SQS is _eventually consistent_. This means that a created
queue won't necessarily show up in an immediate listing of queues,
messages aren't necessarily immediately available to be received, etc.*

You can send, receive, and delete messages:

```clojure
#> (def q (sqs/create-queue client "foo"))
#'cemerick.bandalore-test/q
#> (sqs/send client q "my message body")
{:id "75d5d7a1-2274-4163-97b2-aa4c75f209ee", :body-md5 "05d358de00fc63dd2fa2026b77e112f6"}
#> (sqs/receive client q)
({:attrs #<HashMap {}>, :body "my message body", :body-md5 "05d358de00fc63dd2fa2026b77e112f6",
  :id "75d5d7a1-2274-4163-97b2-aa4c75f209ee",
  :receipt-handle "…very long string…"})
;;
;; …presumably do something with the received message(s)…
;;
#> (sqs/delete client q (first *1))
nil
#> (sqs/receive client q)
()
```

That's cleaner than having to interop directly with the Java SDK, but it's all
pretty pedestrian stuff.  You can do more interesting things with some
simple higher-order functions and other nifty Clojure facilities.

### Enabling SQS Long Polling

[Long polling](http://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/sqs-long-polling.html) reduces the number of empty responses by allowing Amazon SQS service to wait until a message is available in the queue before sending a response. You can enable long polling on an individual receive request by supplying the optional kwarg `:wait-time-seconds`:

  :wait-time-seconds - time in seconds (bewteen 0 and 20) for SQS to wait if there are no messages in the queue. A value of 0 indicates no long polling.

```clojure
   ; ensure our queue is empty to start
#> (get (sqs/queue-attrs client q) "ApproximateNumberOfMessages")
"0"
#> (let [no-polling (future (sqs/receive client q))
         long-polling (future (sqs/receive client q :wait-time-seconds 20))]
    (Thread/sleep 10000)                 ;; Sleep 10s before sending message
    (sqs/send client q "my message body")
    (println (count @no-polling))
    (println (count @long-polling)))
0
1
nil

```

### Sending and receiving Clojure values

SQS' message bodies are strings, so you can stuff anything in them that you can
serialize to a string.  That said, `pr-str` and `read-string` are too handy
to not use, assuming your consumers are using Clojure as well:

```clojure
#> (sqs/send client q (pr-str {:a 5 :b "blah" :c 6.022e23}))
{:id "3756c302-866a-4fcc-a7a3-746e6f531f47", :body-md5 "60052fc2ffb835257c26b9957c6e9ffd"}
#> (-?> (sqs/receive client q) first :body read-string)
{:a 5, :b "blah", :c 6.022E23}
```

### Sending seqs of messages

…with more gratuitous use of `pr-str` and `read-string` to send and receive
Clojure values: 

```clojure
#> (->> [:foo 'bar ["some vector" 42] #{#"silly place for a regex"}]
    (map (comp (partial sqs/send client q) pr-str))
    dorun)
nil
#> (map (comp read-string :body)
    (sqs/receive client q :limit 10))
(bar ["some vector" 42])
#> (map (comp read-string :body)
    (sqs/receive client q :limit 10))
(#{#"silly place for a regex"})
#> (map (comp read-string :body)
    (sqs/receive client q :limit 10))
(:foo)
```

### (Mostly) automatic deletion of consumed messages

When you're done processing a received message, you need to delete it from its
originaing queue:

```clojure
   ; ensure our queue is empty to start
#> (get (sqs/queue-attrs client q) "ApproximateNumberOfMessages")
"0"
#> (dorun (map (partial sqs/send client q) (map str (range 100))))
nil
#> (get (sqs/queue-attrs client q) "ApproximateNumberOfMessages")
"100"

   ; received messages must be removed from the queue or they will
   ; be delivered again after their visibility timeout expires
#> (sqs/receive client q)
(…message seq…)
#> (get (sqs/queue-attrs client q) "ApproximateNumberOfMessages")
"100"
#> (->> (sqs/receive client q) first (sqs/delete client))
nil
#> (get (sqs/queue-attrs client q) "ApproximateNumberOfMessages")
"99"
```

Rather than trying to remember to do this, just use the
`deleting-consumer` "middleware" to produce a function that calls
the message-processing function you provide to it, and then
automatically deletes the processed message from the origining queue:

```clojure
#> (doall (map
            (sqs/deleting-consumer client (comp println :body))
            (sqs/receive client q :limit 10)))
0
4
9
12
26
36
40
44
52
55
(nil nil nil nil nil nil nil nil nil nil)
#> (get (sqs/queue-attrs client q) "ApproximateNumberOfMessages")
"90"
```

### Consuming queues as seqs

seqs being the _lingua franca_ of Clojure collections, it would be helpful if we
could treat an SQS queue as a seq of messages.  While `receive` does return
a seq of messages, each `receive` call is limited to receiving a maximum of
10 messages, and there is no streaming or push counterpart in the SQS API.

The solution to this is `polling-receive`, which returns a lazy seq that
reaches out to SQS as necessary:

```clojure
#> (map (sqs/deleting-consumer client :body)
     (sqs/polling-receive client q :limit 10))
("3" "5" "7" "8" ... "81" "90" "91")
```

`polling-receive` accepts all of the same optional kwargs as `receive` does,
but adds two more to control its usage of `receive`:

  :period - time in ms to wait after an unsuccessful `receive` request (default: 500)
  :max-wait - maximum time in ms to wait to successfully receive messages before terminating
               the lazy seq (default 5000ms)

Often queues are used to direct compute resources, so you'd like to be able to saturate
those boxen with as much work as your queue can offer up.  The obvious solution
is to `pmap` across a seq of incoming messages, which you can do trivially with the seq
provided by `polling-receive`.  Just make sure you tweak the `:max-wait` time so that,
assuming you want to continuously process incoming messages, the seq of messages doesn't
terminate because none have been available for a while.

Here's an example where one thread sends a message once a second for a minute,
and another consumes those messages using a lazy seq provided by `polling-receive`:

```clojure
#> (defn send-dummy-messages
     [client q count]
     (future (doseq [n (range count)]
               (Thread/sleep 100)
               (sqs/send client q (str n)))))
#'cemerick.bandalore-test/send-dummy-messages
#> (defn consume-dummy-messages
     [client q]
     (future (dorun (map (sqs/deleting-consumer client (comp println :body))
                      (sqs/polling-receive client q :max-wait Long/MAX_VALUE :limit 10)))))
#'cemerick.bandalore-test/consume-dummy-messages
#> (consume-dummy-messages client q)               ;; start the consumer
#<core$future_call$reify__5500@a6f00bc: :pending>
#> (send-dummy-messages client q 1000)             ;; start the sender
#<core$future_call$reify__5500@18986032: :pending>
3
4
1
0
2
8
5
7
...
```

You'd presumably want to set up some ways to control your consumer, but hopefully
you see that it would be trivial to parallelize the processing function being
wrapped by `deleting-consumer` using `pmap`, distribute processing among agents
if that's more appropriate, etc. 

## Building Bandalore

Have maven.  From the command line:

```
$ mvn clean verify
```

*The tests are all live*, so:

1. They create and delete queues (though with unique queue names).
2. They aren't written to be particularly efficient w.r.t. SQS usage. If you do decide to run the tests, the associated fees should be trivial (or nonexistent if your account is under the SQS free usage cap).

In any case, you are so warned.  Make a new AWS account dedicated to testing if you're concerned on either count. 

Since the tests are live, you either need to add your AWS credentials to your
`~/.m2/settings.xml` file as properties, or specify them on the command line
using `-D` switches:

```
$ mvn -Daws.id=XXXXXXX -Daws.secret-key=YYYYYYY clean install
```

Or, you can skip the tests entirely:

```
$ mvn -Dmaven.test.skip=true clean install
```

In any case, you'll find a built `.jar` file in the `target` directory, and in
its designated spot in `~/.m2/repository` (assuming you ran `install` rather than
e.g. `package`).

## Need Help?

Ping `cemerick` on freenode irc or twitter if you have questions
or would like to contribute patches.

## License

Copyright © 2011-2013 Chas Emerick and contributors.

Licensed under the EPL. (See the file epl-v10.html.)
