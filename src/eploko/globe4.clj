(ns eploko.globe4
  (:require
   [clojure.core.async :as async :refer [<! >! chan go go-loop]]
   [clojure.core.async.impl.protocols :as async-protocols]))

(deftype UnboundBuffer [!buf]
  async-protocols/Buffer
  (full? [this]
    false)
  (remove! [this]
    (ffirst (swap-vals! !buf pop)))
  (add!* [this itm]
    (swap! !buf conj itm))
  (close-buf! [this] (reset! !buf []))
  clojure.lang.Counted
  (count [this]
    (count @!buf))
  clojure.lang.IDeref
  (deref [this]
    @!buf))

(defn unbound-buf
  []
  (UnboundBuffer. (atom [])))

(defn- join-addr
  [addr child]
  (str addr "/" child))

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

(defprotocol MessageTarget
  (tell! [this msg] "Passes the message `msg` to the underlying mailbox.")
  (addr [this] "Returns its address.")
  (port [this] "Returns its port."))

(deftype ActorRef [addr port]
  MessageTarget
  (tell! [this msg] (go (>! port msg)))
  (addr [this] addr)
  (port [this] port))

(defn- actor-ref
  [addr port]
  (ActorRef. addr port))

(defn send!
  [msg]
  (if-let [^MessageTarget aref (resolve-addr (msg-to msg))]
    (tell! aref msg)
    (println "No such address:" (msg-to msg) "Message dropped:" msg)))

(defn <query!
  ([to subj]
   (<query! to subj nil))
  ([to subj body]
   (let [q-addr "query"
         in (chan)]
     (reg-addr! q-addr in)
     (send! (msg q-addr to subj body))
     (go (let [reply-msg (<! in)]
           (unreg-addr! q-addr)
           (msg-body reply-msg))))))

(defprotocol RefProvider
  (self [this] "Returns own actor ref.")
  (from [this] "Returns the addr of the sender."))

(defprotocol ReplySender
  (reply! [this a] "Sends the reply `a`."))

(defprotocol Spawner
  (spawn! [this actor-name constructor props] "Spawns a new actor."))

(deftype ActorContext [^ActorRef self from]
  RefProvider
  (self [this] self)
  (from [this] from)

  ReplySender
  (reply! [this a]
    (send! (msg self from ::reply a))))

(defn actor-context
  [^ActorRef self from]
  (ActorContext. self from))

(defprotocol Role
  (cleanup [this] "Saves state if needed.")
  (handle [this ^ActorContext ctx subj body] "Handles an incoming message."))

(defn- <stopped-behavior
  [^ActorRef self actor-inst _constructor]
  (go
    (async/close! (port self))
    (cleanup actor-inst)
    (println "Actor stopped:" self)
    nil))

(defn- <default-behavior
  [^ActorRef self actor-inst constructor]
  (go 
    (when-some [msg (<! (port self))]
      (try
        (let [ctx (actor-context self (msg-from msg))
              subj (msg-subj msg)]
          (case (handle actor-inst ctx subj (msg-body msg))
            ::stopped (do #_(unreg-addr! self)
                          [<stopped-behavior actor-inst])
            [<default-behavior actor-inst]))
        (catch Exception e
          (println "exception:" e "actor will restart:" self)
          (cleanup actor-inst)
          [<default-behavior (constructor)])))))

(defprotocol MessageProcessor
  (run-loop! [this] "Starts processing messages."))

(deftype Actor [^ActorRef self constructor]
  MessageProcessor
  (run-loop! [this]
    (go-loop [behavior <default-behavior
              actor-inst (constructor)]
      (when-some [[next-behavior next-inst]
                  (<! (behavior self actor-inst constructor))]
        (recur next-behavior next-inst)))))

(defn actor
  [^ActorRef self constructor]
  (Actor. self constructor))

(defn spawn!
  [^ActorRef parent-ref actor-name constructor props]
  (let [addr (join-addr (addr parent-ref) actor-name)
        port (chan (unbound-buf))
        self (actor-ref addr port)]
    (run-loop! (actor self (partial constructor props)))
    self))

(def ^:const ^:private ROOT-PATH "/")

(deftype ActorSystem []
  Spawner
  (spawn! [this actor-name constructor props]
    (let [addr (str ROOT-PATH actor-name)
          port (chan (unbound-buf))
          aref (actor-ref addr port)]
      (run-loop! (actor aref (partial constructor props)))
      aref)))

(defn- system
  []
  (ActorSystem.))

(defn start-system!
  []
  (let [addr "/"
        port (chan (unbound-buf))
        self (actor-ref addr port)]
    (actor self system)
    self))

(comment
  ;; TODO: Spawn system
  ;; TODO: Spawn children in an actor
  ;; TODO: Make sure children are stopped when the parent is stopped or restarted
  ;; TODO: Let supervisor decide what to do with a failed actor
  (deftype Greeter [greeting !x]
    Role
    (cleanup [this]
      (println "I will restart."))
    (handle [this ctx subj body]
      (case subj
        ::stop ::stopped
        :wassup? (reply! ctx "Wassup!")
        :greet (println (str greeting " " body "!"))
        :inc (swap! !x inc)
        :state? (reply! ctx {:greeting greeting :x @!x})
        :fail! (throw (ex-info "Woohoo!" {}))
        nil)))

  (defn greeter
    [greeting]
    (Greeter. greeting (atom 0)))

  (def as (start-system!))
  
  (def greeter-ref (spawn! as "greeter" greeter "Hello"))
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
  (unreg-addr! "query")

  (ns-unmap (find-ns 'eploko.globe4) 'reply!)
  ,)
