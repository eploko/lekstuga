(ns eploko.globe5
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

(defprotocol ActorRef
  (get-actor-name [this] "Returns the name of the underlying actor.")
  (tell! [this msg] "Sends the message to the underlying actor.")
  (ctrl! [this msg] "Sends the ctrl message to the underlying actor."))

(defprotocol MessageProcessor
  (run-loop! [this] "Processes messages from the mailbox."))

(defprotocol MessageFeed
  (normal-port [this] "Returns the normal port.")
  (ctrl-port [this] "Returns the ctrl port."))

(deftype LocalActorRef [actor-name normal-port ctrl-port]
  ActorRef
  (get-actor-name [this] actor-name)
  (tell! [this msg] (go (>! normal-port msg)))
  (ctrl! [this msg] (go (>! ctrl-port msg)))

  MessageFeed
  (normal-port [this] normal-port)
  (ctrl-port [this] ctrl-port))

(defn mk-local-actor-ref
  [actor-name]
  (LocalActorRef. actor-name (chan (unbound-buf)) (chan (unbound-buf))))

(defprotocol Registry
  (reg! [this k v] "Registers the value under the key. Returns `v`.")
  (unreg! [this k] "Unregs the key."))

(deftype ChildrenRegistry [!children]
  Registry
  (reg! [this k v]
    (swap! !children assoc k v)
    v)
  (unreg! [this k]
    (swap! !children dissoc k)))

(defn- mk-children-registry
  []
  (ChildrenRegistry. (atom {})))

(defprotocol ActorParent
  (spawn! [this actor-name role-f] "Spawns a new child. Returns the child's actor ref.")
  (remove-child! [this actor-name] "Discards the child."))

(defprotocol SelfProvider
  (self [this] "Returns the self ref."))

(defprotocol Role
  (handle [this ctx msg] "Handles the message.")
  (cleanup [this] "Cleans up resources, saves the state."))

(deftype RoleContext [self]
  SelfProvider
  (self [this] self))

(defn- mk-role-context
  [self]
  (RoleContext. self))

(defn- <stopped-behavior
  [actor role-inst _role-f]
  (go
    (async/close! (normal-port (self actor)))
    (async/close! (ctrl-port (self actor)))
    (cleanup role-inst)
    (println "Actor stopped:" (self actor))
    ::terminate-run-loop))

(defn- <default-behavior
  [actor role-inst role-f]
  (let [self-ref (self actor)]
    (go 
      (when-some [[msg port]
                  (async/alts! [(ctrl-port self-ref)
                                (normal-port self-ref)])]
        (if (= port (ctrl-port self-ref))
          (case (first msg)
            ::terminated (do
                           (remove-child! actor (get-actor-name (second msg)))
                           [<default-behavior role-inst])
            [<default-behavior role-inst])
          (try
            (let [ctx (mk-role-context self-ref)]
              (case (handle role-inst ctx msg)
                ::stopped [<stopped-behavior role-inst]
                [<default-behavior role-inst]))
            (catch Exception e
              (println "exception:" e "actor will restart:" actor)
              (cleanup role-inst)
              [<default-behavior (role-f)])))))))

(deftype Actor [parent self role-f ^ChildrenRegistry children]
  SelfProvider
  (self [this] self)
  
  ActorParent
  (spawn! [this actor-name role-f]
    (let [new-actor-ref (mk-local-actor-ref actor-name)
          new-actor (Actor. self new-actor-ref role-f (mk-children-registry))]
      (reg! children actor-name new-actor)
      (run-loop! new-actor)
      new-actor-ref))
  (remove-child! [this actor-name]
    (println "Removing child:" actor-name)
    (unreg! children actor-name))

  MessageProcessor
  (run-loop! [this]
    (go-loop [behavior <default-behavior
              role-inst (role-f)]
      (let [result (<! (behavior this role-inst role-f))]
        (cond
          (= ::terminate-run-loop result)
          (do
            (println "Run loop terminated.")
            (ctrl! parent [::terminated self]))
          (vector? result)
          (recur (first result) (second result))
          :else (throw (ex-info "Invalid behavior result!" {:result result})))))))

(defn- mk-actor
  [parent self role-f]
  (let [new-actor (Actor. parent self role-f (mk-children-registry))]
    (run-loop! new-actor)
    new-actor))

(deftype ActorSystem []
  Role
  (handle [this ctx msg])
  (cleanup [this]))

(defn- mk-actor-system
  []
  (ActorSystem.))

(defn start-system!
  []
  (mk-actor nil (mk-local-actor-ref "") mk-actor-system))

(comment
  (deftype Greeter [greeting !x]
    Role
    (handle [this ctx msg]
      (case (first msg)
        :greet (println (str greeting " " (second msg) "!"))
        :stop ::stopped
        nil))
    (cleanup [this]))

  (defn mk-greeter
    [greeting]
    (Greeter. greeting (atom 0)))

  (def as (start-system!))
  
  (def main-actor-ref
    (spawn! as "greeter" (partial mk-greeter "Hello")))
  (tell! main-actor-ref [:greet "Andrey"])
  (tell! main-actor-ref [:stop])

  (tap> main-actor-ref)

  (ns-unmap (find-ns 'eploko.globe5) 'MessageConsumer)
  ,)
