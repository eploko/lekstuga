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
  (ctrl! [this msg] "Sends the ctrl message to the underlying actor.")
  (reg-watcher! [this actor-ref] "Registers a watcher.")
  (unreg-watcher! [this actor-ref] "Unregisters a watcher.")
  (reg-death! [this] "Notifies watchers the actor is dead."))

(defprotocol MessageProcessor
  (run-loop! [this] "Processes messages from the mailbox."))

(defprotocol MessageFeed
  (normal-port [this] "Returns the normal port.")
  (ctrl-port [this] "Returns the ctrl port."))

(deftype LocalActorRef [actor-name normal-port ctrl-port !dead? !watchers]
  ActorRef
  (get-actor-name [this] actor-name)
  (tell! [this msg] (go (>! normal-port msg)))
  (ctrl! [this msg] (go (>! ctrl-port msg)))
  (reg-watcher! [this watcher]
    (if @!dead?
      (tell! watcher [::terminated this])
      (swap! !watchers conj watcher)))
  (unreg-watcher! [this watcher]
    (swap! !watchers disj watcher))
  (reg-death! [this]
    (reset! !dead? true)
    (doseq [watcher (first (swap-vals! !watchers #{}))]
      (tell! watcher [::terminated this])))

  MessageFeed
  (normal-port [this] normal-port)
  (ctrl-port [this] ctrl-port))

(defn mk-local-actor-ref
  [actor-name]
  (LocalActorRef. actor-name
                  (chan (unbound-buf)) (chan (unbound-buf))
                  (atom false) (atom #{})))

(deftype BubbleRef []
  ActorRef
  (get-actor-name [this] nil)
  (tell! [this msg]
    (println "Bubble hears:" msg))
  (ctrl! [this msg]
    (println "Bubble ponders:" msg))
  (reg-watcher! [this watcher]
    (throw (ex-info "Bubble never dies!" {:watcher watcher})))
  (unreg-watcher! [this watcher]
    (throw (ex-info "You can't escape the bubble!" {:watcher watcher})))
  (reg-death! [this]
    (throw (ex-info "How come the bubble died?!" {}))))

(defn- mk-bubble-ref
  []
  (BubbleRef.))

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

(defprotocol Spawner
  (spawn! [this actor-name role-f] "Spawns a new child. Returns the child's actor ref."))

(defprotocol ActorRefWatcher
  (watch [this target-ref] "Waits until the target-ref's actor terminates and notifies about it.")
  (unwatch [this target-ref] "Deregisters interest in watching."))

(defprotocol ActorParent
  (remove-child! [this child-ref] "Discards the child."))

(defprotocol SelfProvider
  (self [this] "Returns the self ref."))

(defprotocol Role
  (init [this ctx] "Bootstraps the role.")
  (handle [this ctx msg] "Handles the message.")
  (cleanup [this ctx] "Cleans up resources, saves the state."))

(deftype ActorContext [self-ref ^ChildrenRegistry children]
  SelfProvider
  (self [this] self-ref)

  Spawner
  (spawn! [this actor-name role-f]
    (let [child-ref (mk-local-actor-ref actor-name)]
      (reg! children child-ref
            (mk-actor self-ref child-ref role-f))))

  ActorParent
  (remove-child! [this child-ref]
    (println "Removing child:" child-ref)
    (unreg! children child-ref))

  ActorRefWatcher
  (watch [this target-ref]
    (reg-watcher! target-ref self-ref))
  (unwatch [this target-ref]
    (unreg-watcher! target-ref self-ref)))

(defn- mk-actor-context
  [self-ref]
  (ActorContext. self-ref (mk-children-registry)))

(defn- init-role
  [role-f ctx]
  (let [role-inst (role-f)]
    (init role-inst ctx)
    role-inst))

(defn- <stopped-behavior
  [_actor ctx role-inst _role-f]
  (go
    (async/close! (normal-port (self ctx)))
    (async/close! (ctrl-port (self ctx)))
    (cleanup role-inst ctx)
    (println "Actor stopped:" (self ctx))
    ::terminate-run-loop))

(declare <default-behavior)

(defn- <exception-behavior
  [e _actor ctx role-inst role-f]
  (go
    (println "exception:" e "actor will restart:" (self ctx))
    (cleanup role-inst ctx)
    [<default-behavior (init-role role-f ctx)]))

(defn- <terminated-behavior
  [child-ref _actor ctx role-inst _role-f]
  (go (remove-child! ctx child-ref)
      [<default-behavior role-inst]))

(defn- <default-behavior
  [_actor ctx role-inst _role-f]
  (let [self-ref (self ctx)]
    (go 
      (when-some [[msg port]
                  (async/alts! [(ctrl-port self-ref)
                                (normal-port self-ref)])]
        (if (= port (ctrl-port self-ref))
          (case (first msg)
            ::terminated
            [(partial <terminated-behavior (second msg)) role-inst]
            [<default-behavior role-inst])
          (try
            (case (handle role-inst ctx msg)
              ::stopped [<stopped-behavior role-inst]
              [<default-behavior role-inst])
            (catch Exception e
              [(partial <exception-behavior e) role-inst])))))))

(declare mk-actor)

(deftype Actor [parent self-ref role-f ctx]
  SelfProvider
  (self [this] self-ref)

  MessageProcessor
  (run-loop! [this]
    (go-loop [behavior <default-behavior
              role-inst (init-role role-f ctx)]
      (let [result
            (<! (behavior this ctx role-inst role-f))]
        (cond
          (= ::terminate-run-loop result)
          (do
            (println "Run loop terminated.")
            (ctrl! parent [::terminated self-ref])
            (reg-death! self-ref))
          (vector? result)
          (recur (first result) (second result))
          :else (throw (ex-info "Invalid behavior result!" {:result result})))))))

(defn- mk-actor
  [parent self-ref role-f]
  (run-loop! (Actor. parent self-ref role-f
                     (mk-actor-context self-ref)))
  self-ref)

(def ^:private user-subsystem-default-state
  {:main-actor-ref nil})

(deftype UserSubsystem [actor-name role-f result-ch !state]
  Role
  (init [this ctx]
    (println "In user subsystem init...")
    (let [main-actor-ref (spawn! ctx actor-name role-f)]
      (go (>! result-ch main-actor-ref)
          (async/close! result-ch))
      (swap! !state assoc :main-actor-ref main-actor-ref)
      (watch ctx main-actor-ref)))
  (handle [this ctx msg]
    (case (first msg)
      ::terminated
      (when (= (second msg) (:main-actor-ref @!state))
        (println "Main actor is dead. Stopping the user guard...")
        ::stopped)
      nil))
  (cleanup [this ctx]
    (when-some [main-actor-ref (:main-actor-ref @!state)]
      (unwatch ctx main-actor-ref))
    (reset! !state user-subsystem-default-state)))

(defn- mk-user-subsystem
  [actor-name role-f result-ch]
  (UserSubsystem. actor-name role-f result-ch
                  (atom user-subsystem-default-state)))

(def ^:private actor-system-default-state
  {:user-guard-ref nil})

(deftype ActorSystem [actor-name role-f result-ch !state]
  Role
  (init [this ctx]
    (println "In actor system init...")
    (let [user-guard-ref
          (spawn! ctx "user" (partial mk-user-subsystem actor-name role-f result-ch))]
      (swap! !state assoc :user-guard-ref user-guard-ref)
      (watch ctx user-guard-ref)))
  (handle [this ctx msg]
    (case (first msg)
      ::terminated
      (when (= (second msg) (:user-guard-ref @!state))
        (println "The user guard is dead. Stopping the actor system...")
        ::stopped)
      nil))
  (cleanup [this ctx]
    (when-some [user-guard-ref (:user-guard-ref @!state)]
      (unwatch ctx user-guard-ref))
    (reset! !state actor-system-default-state)))

(defn- mk-actor-system
  [actor-name role-f result-ch]
  (ActorSystem. actor-name role-f result-ch
                (atom actor-system-default-state)))

(defn <start-system!
  [actor-name role-f]
  (let [result-ch (chan)]
    (mk-actor (mk-bubble-ref) (mk-local-actor-ref "")
              (partial mk-actor-system actor-name role-f result-ch))
    result-ch))

(comment
  ;; TODO: Rename RoleContext to ActorContext
  ;; TODO: Move children to the actor context
  (deftype Greeter [greeting !x]
    Role
    (init [this ctx]
      (println "In greeter init..."))
    (handle [this ctx msg]
      (case (first msg)
        :greet (println (str greeting " " (second msg) "!"))
        :stop ::stopped
        nil))
    (cleanup [this ctx]))

  (defn mk-greeter
    [greeting]
    (Greeter. greeting (atom 0)))

  (def !main-actor-ref (atom nil))

  (go (reset! !main-actor-ref (<! (<start-system! "greeter" (partial mk-greeter "Hello"))))
      (println "Main actor ref received:" @!main-actor-ref))
  
  (tell! @!main-actor-ref [:greet "Andrey"])
  (tell! @!main-actor-ref [:stop])

  (tap> @!main-actor-ref)

  (ns-unmap (find-ns 'eploko.globe5) 'mk-role-context)
  ,)
