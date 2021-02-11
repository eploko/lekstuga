(ns eploko.globe5
  (:require
   [clojure.core.async :as async :refer [<! >! chan go go-loop]]
   [clojure.core.async.impl.protocols :as async-protocols]
   [clojure.string :as str]))

(defn <wait-for
  [& chs]
  (go (<! (async/map vector chs))))

(defprotocol ActorRef
  (parent [this] "Returns the parent ref.")
  (get-path [this] "Returns its path.")
  (tell! [this msg] "Sends the message to the underlying actor.")
  (ctrl! [this msg] "Sends the ctrl message to the underlying actor.")
  (<ask! [this msg] "Sends the message and waits for a reply")
  (reg-watcher! [this actor-ref] "Registers a watcher.")
  (unreg-watcher! [this actor-ref] "Unregisters a watcher.")
  (reg-death! [this] "Notifies watchers the actor is dead."))

(defn log!
  [actor-ref & args]
  (->> args
       (str/join " ")
       (format "%s: %s" actor-ref)
       println))

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

(defprotocol MessageFeed
  (normal-port [this] "Returns the normal port.")
  (ctrl-port [this] "Returns the ctrl port."))

(deftype LocalActorRef [parent-ref actor-name normal-port ctrl-port !dead? !watchers]
  ActorRef
  (parent [this] parent-ref)
  (get-path [this] (str (get-path parent-ref) "/" actor-name))
  (tell! [this msg] (go (>! normal-port msg)))
  (ctrl! [this msg] (go (>! ctrl-port msg)))
  (<ask! [this msg]
    (go (tell! this msg)
        nil))
  (reg-watcher! [this watcher]
    (if @!dead?
      (tell! watcher [::terminated this])
      (swap! !watchers conj watcher)))
  (unreg-watcher! [this watcher]
    (swap! !watchers disj watcher))
  (reg-death! [this]
    (reset! !dead? true)
    (doseq [watcher (first (swap-vals! !watchers #{}))]
      (tell! watcher [::terminated this]))
    (ctrl! parent-ref [::terminated this]))

  MessageFeed
  (normal-port [this] normal-port)
  (ctrl-port [this] ctrl-port)

  Object
  (toString [this]
    (get-path this)))

(defn mk-local-actor-ref
  [parent-ref actor-name]
  (LocalActorRef. parent-ref actor-name
                  (chan (unbound-buf)) (chan (unbound-buf))
                  (atom false) (atom #{})))

(deftype BubbleRef []
  ActorRef
  (parent [this] nil)
  (get-path [this] "globe:/")
  (tell! [this msg]
    (log! this "was told:" msg))
  (ctrl! [this msg]
    (log! this "was signalled:" msg))
  (<ask! [this msg]
    (log! this "was asked:" msg)
    (go nil))
  (reg-watcher! [this watcher]
    (throw (ex-info "Bubble never dies!" {:watcher watcher})))
  (unreg-watcher! [this watcher]
    (throw (ex-info "You can't escape the bubble!" {:watcher watcher})))
  (reg-death! [this]
    (throw (ex-info "How come the bubble died?!" {})))

  Object
  (toString [this]
    (get-path this)))

(defn- mk-bubble-ref
  []
  (BubbleRef.))

(defprotocol Spawner
  (spawn! [this actor-name actor-f] "Spawns a new child. Returns the child's actor ref."))

(defprotocol ActorRefWatcher
  (watch [this target-ref] "Waits until the target-ref's actor terminates and notifies about it.")
  (unwatch [this target-ref] "Deregisters interest in watching."))

(defprotocol ActorParent
  (remove-child! [this child-ref] "Discards the child.")
  (stop-all-children! [this] "Stops all of its children."))

(defprotocol SelfProvider
  (self [this] "Returns the self ref."))

(defprotocol ActorProvider
  (get-actor-f [this] "Returns the actor constructor fn."))

(defprotocol Actor
  (init [this ctx] "Bootstraps the actor.")
  (handle [this ctx msg] "Handles the message.")
  (cleanup [this ctx] "Cleans up resources, saves the state."))

(declare spawn-actor!)

(deftype ActorContext [self-ref actor-f !children]
  SelfProvider
  (self [this] self-ref)

  ActorProvider
  (get-actor-f [this] actor-f)

  Spawner
  (spawn! [this actor-name actor-f]
    (let [child-ref (mk-local-actor-ref self-ref actor-name)]
      (swap! !children conj child-ref)
      (spawn-actor! child-ref actor-f)))

  ActorParent
  (remove-child! [this child-ref]
    (log! this "removing child:" child-ref)
    (swap! !children disj child-ref))
  (stop-all-children! [this]
    (go (<! (apply <wait-for
                   (map #(<ask! % [::stop])
                        @!children)))))

  ActorRefWatcher
  (watch [this target-ref]
    (reg-watcher! target-ref self-ref))
  (unwatch [this target-ref]
    (unreg-watcher! target-ref self-ref)))

(defn- mk-actor-context
  [self-ref actor-f]
  (ActorContext. self-ref actor-f (atom #{})))

(defn- init-actor
  [ctx]
  (let [actor-f (get-actor-f ctx)
        actor-inst (actor-f)]
    (init actor-inst ctx)
    actor-inst))

(declare <default-behavior)

(defn- <init-behavior
  [ctx _actor-inst]
  (go [<default-behavior (init-actor ctx)]))

(defn- <stopped-behavior
  [ctx actor-inst]
  (go
    (let [self-ref (self ctx)]
      (async/close! (normal-port self-ref))
      (async/close! (ctrl-port self-ref))
      (cleanup actor-inst ctx)
      (stop-all-children! ctx)
      (log! self-ref "actor stopped")
      (reg-death! self-ref))
    ::terminate-run-loop))

(defn- <exception-behavior
  [e ctx actor-inst]
  (go
    (log! (self ctx) "exception:" e)
    (log! (self ctx) "actor will restart")
    (cleanup actor-inst ctx)
    [<default-behavior (init-actor ctx)]))

(defn- <terminated-behavior
  [child-ref ctx actor-inst]
  (go (remove-child! ctx child-ref)
      [<default-behavior actor-inst]))

(defn- <default-behavior
  [ctx actor-inst]
  (let [self-ref (self ctx)]
    (go 
      (when-some [[msg port]
                  (async/alts! [(ctrl-port self-ref)
                                (normal-port self-ref)])]
        (if (= port (ctrl-port self-ref))
          (case (first msg)
            ::terminated
            [(partial <terminated-behavior (second msg)) actor-inst]
            [<default-behavior actor-inst])
          (try
            (case (first msg)
              ::stop [<stopped-behavior actor-inst]
              (case (handle actor-inst ctx msg)
                ::stopped [<stopped-behavior actor-inst]
                [<default-behavior actor-inst]))
            (catch Exception e
              [(partial <exception-behavior e) actor-inst])))))))

(defn- run-loop!
  [ctx]
  (go-loop [[behavior actor-inst]
            [<init-behavior nil]]
    (let [result (<! (behavior ctx actor-inst))]
      (case result
        ::terminate-run-loop (log! (self ctx) "run loop terminated")
        (recur result)))))

(defn- spawn-actor!
  [self-ref actor-f]
  (run-loop! (mk-actor-context self-ref actor-f))
  self-ref)

(def ^:private user-subsystem-default-state
  {:main-actor-ref nil})

(deftype UserSubsystem [actor-name actor-f result-ch !state]
  Actor
  (init [this ctx]
    (log! (self ctx) "In user subsystem init...")
    (let [main-actor-ref (spawn! ctx actor-name actor-f)]
      (go (>! result-ch main-actor-ref)
          (async/close! result-ch))
      (swap! !state assoc :main-actor-ref main-actor-ref)
      (watch ctx main-actor-ref)))
  (handle [this ctx msg]
    (case (first msg)
      ::terminated
      (when (= (second msg) (:main-actor-ref @!state))
        (log! (self ctx) "Main actor is dead. Stopping the user guard...")
        ::stopped)
      nil))
  (cleanup [this ctx]
    (when-some [main-actor-ref (:main-actor-ref @!state)]
      (unwatch ctx main-actor-ref))
    (reset! !state user-subsystem-default-state)))

(defn- mk-user-subsystem
  [actor-name actor-f result-ch]
  (UserSubsystem. actor-name actor-f result-ch
                  (atom user-subsystem-default-state)))

(def ^:private actor-system-default-state
  {:user-guard-ref nil})

(deftype ActorSystem [actor-name actor-f result-ch !state]
  Actor
  (init [this ctx]
    (log! (self ctx) "In actor system init...")
    (let [user-guard-ref
          (spawn! ctx "user" (partial mk-user-subsystem actor-name actor-f result-ch))]
      (swap! !state assoc :user-guard-ref user-guard-ref)
      (watch ctx user-guard-ref)))
  (handle [this ctx msg]
    (case (first msg)
      ::terminated
      (when (= (second msg) (:user-guard-ref @!state))
        (log! (self ctx) "The user guard is dead. Stopping the actor system...")
        ::stopped)
      nil))
  (cleanup [this ctx]
    (when-some [user-guard-ref (:user-guard-ref @!state)]
      (unwatch ctx user-guard-ref))
    (reset! !state actor-system-default-state)))

(defn- mk-actor-system
  [actor-name actor-f result-ch]
  (ActorSystem. actor-name actor-f result-ch
                (atom actor-system-default-state)))

(defn <start-system!
  [actor-name actor-f]
  (let [result-ch (chan)]
    (spawn-actor! (mk-local-actor-ref (mk-bubble-ref) "system@localhost")
                  (partial mk-actor-system actor-name actor-f result-ch))
    result-ch))

(comment
  (deftype MyHero []
    Actor
    (init [this ctx]
      (log! (self ctx) "In MyHero init..."))
    (handle [this ctx msg])
    (cleanup [this ctx]))

  (defn mk-my-hero
    []
    (MyHero.))
  
  (deftype Greeter [greeting !x]
    Actor
    (init [this ctx]
      (log! (self ctx) "In greeter init...")
      (spawn! ctx "my-hero" mk-my-hero))
    (handle [this ctx msg]
      (case (first msg)
        :greet (log! (self ctx)
                     (format "%s %s!" greeting (second msg)))
        :stop ::stopped
        nil))
    (cleanup [this ctx]))

  (defn mk-greeter
    [greeting]
    (Greeter. greeting (atom 0)))

  (def !main-actor-ref (atom nil))

  (go (reset! !main-actor-ref (<! (<start-system! "greeter" (partial mk-greeter "Hello")))))
  
  (tell! @!main-actor-ref [:greet "Andrey"])
  (tell! @!main-actor-ref [:stop])

  (tap> @!main-actor-ref)

  (ns-unmap (find-ns 'eploko.globe5) 'log)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'eploko.globe5/")
          (map str
               (vals
                (ns-map (find-ns 'eploko.globe5)))))
  ,)
