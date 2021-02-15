(ns globe.core
  (:require [clojure.core.async :as async :refer [<! >! chan go go-loop]]
            [clojure.core.match :refer [match]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [globe.async :refer [<take-all chan? unbound-buf]]))

(defprotocol ActorRef
  (parent [this] "Returns the parent ref.")
  (get-path [this] "Returns its path.")
  (reg-watcher! [this actor-ref] "Registers a watcher.")
  (unreg-watcher! [this actor-ref] "Unregisters a watcher.")
  (reg-death! [this] "Notifies watchers the actor is dead."))

(defprotocol Listening
  (tell! [this msg] "Sends the message to the underlying actor."))

(defprotocol Replying
  (<ask! [this msg] "Sends the message and waits for a reply"))

(defn actor-ref?
  [x]
  (satisfies? ActorRef x))

(s/def ::body some?)
(s/def ::from actor-ref?)
(s/def ::signal? true?)
(s/def ::msg (s/keys :req-un [::body]
                     :opt-un [::from ::signal?]))

(defn log!
  [actor-ref & args]
  (->> args
       (str/join " ")
       (format "%s: %s" actor-ref)
       println))

(defprotocol MessageFeed
  (normal-port [this] "Returns the normal port.")
  (ctrl-port [this] "Returns the ctrl port."))

(deftype ReplyRef [out-ch]
  Listening
  (tell! [this msg]
    (go (>! out-ch msg)
        (async/close! out-ch))))

(defn- mk-reply-ref
 [ch]
 (ReplyRef. ch))

(deftype LocalActorRef [parent-ref actor-name normal-port ctrl-port !dead? !watchers]
  Listening
  (tell! [this msg]
    {:pre [(s/valid? ::msg msg)]}
    (go (>! (if (:signal? msg) ctrl-port normal-port)
            (:body msg))))

  Replying
  (<ask! [this msg]
    {:pre [(s/valid? ::msg msg)]}
    (let [ch (chan)
          timeout-ch (async/timeout 1000)]
      (tell! this (assoc msg :from (mk-reply-ref ch)))
      (go
        (let [[v p] (async/alts! [ch timeout-ch])]
          (if (= p timeout-ch)
            (do
              (log! this "<ask! timed out:" msg)
              nil)
            v)))))
  
  ActorRef
  (parent [this] parent-ref)
  (get-path [this] (str (get-path parent-ref) "/" actor-name))
  (reg-watcher! [this watcher]
    (if @!dead?
      (tell! watcher {:from this :body ::terminated})
      (swap! !watchers conj watcher)))
  (unreg-watcher! [this watcher]
    (swap! !watchers disj watcher))
  (reg-death! [this]
    (reset! !dead? true)
    (doseq [watcher (first (swap-vals! !watchers #{}))]
      (tell! watcher {:from this :body ::terminated}))
    (tell! parent-ref {:signal? true :from this :body ::child-terminated}))

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
  Listening
  (tell! [this msg]
    {:pre [(s/valid? ::msg msg)]}
    (log! this "was told:" msg))

  ActorRef
  (parent [this] nil)
  (get-path [this] "globe:/")
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
  (spawn! [this actor-name behaviors props] "Spawns a new child. Returns the child's actor ref."))

(defprotocol ActorRefWatcher
  (watch [this target-ref] "Waits until the target-ref's actor terminates and notifies about it.")
  (unwatch [this target-ref] "Deregisters interest in watching."))

(defprotocol ActorParent
  (remove-child! [this child-ref] "Discards the child.")
  (<stop-all-children! [this] "Stops all of its children."))

(defprotocol SelfProvider
  (self [this] "Returns the self ref."))

(defprotocol ActorProvider
  (get-props [this] "Returns the actor's props.")
  (get-behavior [this id] "Returns the behavior for the given `id`."))

(declare spawn-actor!)

(defn- ctx-rcv
  [ctx]
  (go 
    (let [self-ref (self ctx)
          [msg _port] (async/alts! [(ctrl-port self-ref)
                                    (normal-port self-ref)])]
      (log! self-ref "got message:" msg)
      msg)))

(defmacro receive
  [ctx state & clauses]
  (list 'go
        (list 'try
              (concat (list 'match [(list '<! (list 'ctx-rcv ctx))]
                            [{:from 'sender :body ::stop}] [::stopped state 'sender]
                            [{:from 'sender :body ::child-terminated}] [::child-terminated state 'sender])
                      clauses)
              (list 'catch 'Exception 'e
                    [::exception state 'e]))))

(defn- default-init-behavior
  [_ctx _props]
  [::receive nil])

(defn noop-behavior
  [ctx state]
  (receive ctx state
           [ignored-msg]
           (do
             (log! (self ctx) "Message ignored:" ignored-msg)
             nil)))

(defn- default-cleanup-behavior
  [_ctx _state]
  [::done nil])

(defn- default-stopped-behavior
  [ctx state stop-sender]
  (go
    (let [self-ref (self ctx)
          cleanup-behavior (get-behavior ctx ::cleanup)]
      (log! self-ref "stopping requested by:" stop-sender)
      (async/close! (normal-port self-ref))
      (async/close! (ctrl-port self-ref))
      (cleanup-behavior ctx state)
      (<! (<stop-all-children! ctx))
      (log! self-ref "actor stopped")
      (reg-death! self-ref)
      (when stop-sender
        (tell! stop-sender {:from self-ref :body ::stopped})))
    ::terminate-run-loop))

(defn- default-child-terminated-behavior
  [ctx state who]
  (remove-child! ctx who)
  [::receive state])

(defn- default-exception-behavior
  [ctx state e]
  (log! (self ctx) "exception:" e)
  (log! (self ctx) "actor will restart")
  (let [cleanup-behavior (get-behavior ctx ::cleanup)
        init-behavior (get-behavior ctx ::init)
        props (get-props ctx)]
    (cleanup-behavior ctx state)
    [::receive (init-behavior ctx props)]))

(def ^:private default-behaviors
  {::init default-init-behavior
   ::receive noop-behavior
   ::cleanup default-cleanup-behavior
   ::stopped default-stopped-behavior
   ::child-terminated default-child-terminated-behavior
   ::exception default-exception-behavior})

(deftype ActorContext [self-ref behaviors props !children]
  SelfProvider
  (self [this] self-ref)

  ActorProvider
  (get-props [this] props)
  (get-behavior [this id]
    (get (merge default-behaviors behaviors) id))

  Spawner
  (spawn! [this actor-name behaviors props]
    (let [child-ref (mk-local-actor-ref self-ref actor-name)]
      (swap! !children conj child-ref)
      (spawn-actor! child-ref behaviors props)))

  ActorParent
  (remove-child! [this child-ref]
    (log! this "removing child:" child-ref)
    (swap! !children disj child-ref))
  (<stop-all-children! [this]
    (go
      (log! self-ref "stopping all children...")
      (log! self-ref "all children stopped:"
            (<! (apply
                 <take-all
                 (map #(<ask! % {:body ::stop})
                      @!children)))
            (reset! !children #{}))
      true))

  ActorRefWatcher
  (watch [this target-ref]
    (reg-watcher! target-ref self-ref))
  (unwatch [this target-ref]
    (unreg-watcher! target-ref self-ref)))

(defn- mk-actor-context
  [self-ref behaviors props]
  (ActorContext. self-ref behaviors props (atom #{})))

(defn- run-loop!
  [ctx]
  (go-loop [behavior (get-behavior ctx ::init)
            state [(get-props ctx)]]
    (let [call-result (apply behavior ctx state)
          read-result (if (chan? call-result) (<! call-result) call-result)
          _ (log! (self ctx) "read-result:" read-result)

          [next-behavior & next-state]
          (cond 
            (nil? read-result) [behavior state]
            (fn? read-result) [read-result state]
            (vector? read-result) read-result)]
      (if (= next-behavior ::terminate-run-loop)
        (log! (self ctx) "run loop terminated")
        (recur (if (fn? next-behavior)
                 next-behavior
                 (get-behavior ctx next-behavior))
               next-state)))))

(defn- spawn-actor!
  [self-ref behaviors props]
  (run-loop! (mk-actor-context self-ref behaviors props))
  self-ref)

(defn- user-guard-receive-behavior
  [ctx {:keys [main-actor-ref] :as state}]
  (receive ctx state
           [{:from main-actor-ref :body ::terminated}]
           (do
             (log! (self ctx) "Main actor is dead. Stopping the user guard...")
             [::stopped state nil])
           :else [::receive state]))

(defn- user-guard-init-behavior
  [ctx [result-ch actor-name behavior-m actor-args]]
  (log! (self ctx) "In user subsystem init...")
  (let [main-actor-ref (spawn! ctx actor-name behavior-m actor-args)]
    (go (>! result-ch main-actor-ref)
        (async/close! result-ch))
    (watch ctx main-actor-ref)
    [user-guard-receive-behavior {:main-actor-ref main-actor-ref}]))

(defn- user-guard-cleanup-behavior
  [ctx {:keys [main-actor-ref] :as state}]
  (when main-actor-ref (unwatch ctx main-actor-ref))
  [::done (assoc state :main-actor-ref nil)])

(def ^:private user-guard
  {::init user-guard-init-behavior
   ::receive user-guard-receive-behavior
   ::cleanup user-guard-cleanup-behavior})

(defn- actor-system-receive-behavior
  [ctx {:keys [user-guard-ref] :as state}]
  (receive ctx state
           [{:from user-guard-ref :body ::terminated}]
           (do
             (log! (self ctx) "The user guard is dead. Stopping the actor system...")
             [::stopped state nil])
           :else [::receive state]))

(defn- actor-system-init-behavior
  [ctx [result-ch actor-name behavior-m actor-args]]
  (log! (self ctx) "In actor system init...")
  (let [user-guard-ref
        (spawn! ctx "user" user-guard [result-ch actor-name behavior-m actor-args])]
    (watch ctx user-guard-ref)
    [::receive {:user-guard-ref user-guard-ref}]))

(defn- actor-system-cleanup-behavior
  [ctx {:keys [user-guard-ref] :as state}]
  (when user-guard-ref (unwatch ctx user-guard-ref))
  [::done (assoc state :user-guard-ref nil)])

(def ^:private actor-system
  {::init actor-system-init-behavior
   ::receive actor-system-receive-behavior
   ::cleanup actor-system-cleanup-behavior})

(defn <start-system!
  [actor-name behavior-m actor-args]
  (let [result-ch (chan)]
    (spawn-actor! (mk-local-actor-ref (mk-bubble-ref) "system@localhost")
                  actor-system [result-ch actor-name behavior-m actor-args])
    result-ch))

(comment
  (defn my-hero-init-behavior
    [ctx _props]
    (log! (self ctx) "In MyHero init...")
    noop-behavior)
  
  (def my-hero
    {::init my-hero-init-behavior})

  (defn greeter-receive-behavior
    [ctx state]
    (receive ctx state
             [{:body [:greet who]}]
             (do
               (println (format "%s %s!" (:greeting state) who))
               [::receive state])
             :else [::receive state]))

  (defn greeter-init-behavior
    [ctx [greeting]]
    (spawn! ctx "my-hero" my-hero [])
    [::receive {:greeting greeting :x 0}])

  (def greeter
    {::init greeter-init-behavior
     ::receive greeter-receive-behavior})

  (def !main-actor-ref (atom nil))

  (go (reset! !main-actor-ref (<! (<start-system! "greeter" greeter ["Hello"]))))
  
  (tell! @!main-actor-ref {:body [:greet "Andrey"]})
  (tell! @!main-actor-ref {:body ::stop})

  (tap> @!main-actor-ref)

  (ns-unmap (find-ns 'eploko.globe5) 'log)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'eploko.globe5/")
          (map str
               (vals
                (ns-map (find-ns 'eploko.globe5)))))
  ,)
