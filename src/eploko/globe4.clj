(ns eploko.globe4
  (:require
   [clojure.core.async :as async :refer [<! >! chan go go-loop]]))

(defonce ^:private !addrs (atom {}))

(defn addr!
  [addr port]
  (if port
    (swap! !addrs assoc addr port)
    (swap! !addrs dissoc addr)))

(defn join-addr
  [addr child]
  (str addr "/" child))

(defn resolve-addr
  [addr]
  (get @!addrs addr))

(defn msg
  ([to subj body]
   (msg nil to subj body))
  ([from to subj body]
   {:from from
    :to to
    :subj subj
    :body body}))

(defn- msg-from
  [msg]
  (:from msg))

(defn- msg-to
  [msg]
  (:to msg))

(defn- msg-subj
  [msg]
  (:subj msg))

(defn- msg-body
  [msg]
  (:body msg))

(defn send!
  [msg]
  (if-let [port (resolve-addr (msg-to msg))]
    (go (>! port msg))
    (println "No such address:" (msg-to msg) "Message dropped:" msg)))

(defn <query!
  ([to subj]
   (<query! to subj nil))
  ([to subj body]
   (let [q-addr "query"
         in (chan)
         out (go (let [reply-msg (<! in)]
                   (addr! q-addr nil)
                   (msg-body reply-msg)))]
     (addr! q-addr in)
     (send! (msg q-addr to subj body))
     out)))

(defn spawn!
  [parent-addr actor-name actor-fn]
  (let [addr (join-addr parent-addr actor-name)]
    (addr! addr (actor-fn addr))
    addr))

(defn- mk-ctx
  [self from]
  {:self self
   :from from})

(defn ctx-self
  [ctx]
  (:self ctx))

(defn ctx-from
  [ctx]
  (:from ctx))

(defn- <stopped-behavior
  [self port _constructor _h]
  (go
    (async/close! port)
    (println "Actor stopped:" self)
    nil))

(defn- <default-behavior
  [self port constructor inst]
  (let [{:keys [cleanup handle]
         :or {cleanup (fn [])}}
        inst]
    (go 
      (when-some [msg (<! port)]
        (try
          (let [ctx (mk-ctx self (msg-from msg))]  
            (case (handle ctx (msg-subj msg) (msg-body msg))
              ::stopped (do (addr! self nil)
                            [<stopped-behavior inst])
              [<default-behavior inst]))
          (catch Exception e
            (println "exception:" e "actor will restart:" self)
            (cleanup)
            [<default-behavior (constructor)]))))))

(defn actor
  [init-f]
  (fn [props]
    (fn [self]
      (let [port (chan 10)
            constructor (partial init-f props)]
        (go-loop [behavior <default-behavior
                  inst (constructor)]
          (when-some [[next-behavior next-inst] (<! (behavior self port constructor inst))]
            (recur next-behavior next-inst)))
        port))))

(defn reply!
  [ctx a]
  (send! (msg (ctx-self ctx) (ctx-from ctx) ::reply a)))

(comment
  (def greeter
    (actor
     (fn [props]
       (println "Initializing...")
       (let [greeting props
             state (atom {:x 0})]
         {:cleanup
          (fn []
            (println "I will restart."))
          :handle
          (fn [ctx subj body]
            (case subj
              ::stop ::stopped
              :wassup? (reply! ctx "Wassup!")
              :greet (println (str greeting " " body "!"))
              :inc (swap! state update :x inc)
              :state? (reply! ctx @state)
              :fail! (throw (ex-info "Woohoo!" {}))
              nil))}))))

  (def greeter-addr (spawn! nil "greeter" (greeter "Hello")))
  (send! (msg greeter-addr :greet "Andrey"))
  (send! (msg greeter-addr :inc nil))
  (go 
    (println "state:" (<! (<query! greeter-addr :state?))))
  (go 
    (println "response:" (<! (<query! greeter-addr :wassup?))))
  (send! (msg greeter-addr ::stop nil))
  (send! (msg greeter-addr :fail! nil))

  (tap> @!addrs)
  (go (>! (resolve-addr "query") (msg "query" nil "yo")))
  (addr! "query" nil)

  (ns-unmap (find-ns 'eploko.globe4) 'stop!)
  ,)
