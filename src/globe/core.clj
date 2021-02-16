(ns globe.core
  (:require [clojure.core.async :as async :refer [<! >! chan go go-loop]]
            [clojure.core.match :refer [match]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [globe.async :refer [<take-all chan? unbound-buf]])
  (:import [clojure.lang Atom]))

(defn atom?
  [x]
  (instance? Atom x))

(s/def ::non-empty-string (s/and string? #(> (count %) 0)))
(s/def ::port chan?)
(s/def ::signal-port ::port)
(s/def ::normal-port ::port)
(s/def ::scope keyword?)
(s/def ::id ::non-empty-string)
(s/def ::has-id (s/keys :req [::id]))
(s/def ::parent (s/or :actor-ref ::actor-ref
                      :nil nil?))
(s/def ::watchers atom?)
(s/def ::dead? atom?)
(s/def ::local-actor-ref
  (s/keys :req [::scope ::id ::parent ::signal-port ::normal-port ::dead? ::watchers]))
(s/def ::bubble-ref
  (s/keys :req [::scope ::dead? ::watchers]))
(s/def ::actor-ref (s/or :bubble-ref ::bubble-ref
                         :local-actor-ref ::local-actor-ref))
(s/def ::body some?)
(s/def ::from ::actor-ref)
(s/def ::signal? true?)
(s/def ::msg (s/keys :req-un [::body]
                     :opt-un [::from ::signal?]))


(defmulti get-path
  (fn [actor-ref]
    {:pre [(s/valid? ::actor-ref actor-ref)]}
    (::scope actor-ref)))

(defmethod get-path :local
  [actor-ref]
  {:pre [(s/valid? ::actor-ref actor-ref)]}
  (str (get-path (::parent actor-ref)) "/" (::id actor-ref)))

(defmethod get-path :bubble
  [actor-ref]
  {:pre [(s/valid? ::actor-ref actor-ref)]}
  "globe:/")

(defn log!
  [actor-ref & args]
  (->> args
       (str/join " ")
       (format "%s: %s" (get-path actor-ref))
       println))

(defn local-actor-ref
  [parent id]
  {:pre [(s/valid? ::parent parent)
         (s/valid? ::id id)]
   :post [(s/valid? ::actor-ref %)]}
  {::scope :local
   ::id id
   ::parent parent
   ::signal-port (chan (unbound-buf))
   ::normal-port (chan (unbound-buf))
   ::dead? (atom false)
   ::watchers (atom #{})})

(defn bubble-ref
  []
  {:post [(s/valid? ::actor-ref %)]}
  {::scope :bubble
   ::dead? (atom false)
   ::watchers (atom #{})})

(defmulti tell!
  (fn [actor-ref msg]
    {:pre [(s/valid? ::actor-ref actor-ref)
           (s/valid? ::msg msg)]}
    (::scope actor-ref)))

(defmethod tell! :local
  [actor-ref msg]
  (go (>! (actor-ref (if (:signal? msg) ::signal-port ::normal-port))
          msg)))

(defmethod tell! :bubble
  [actor-ref msg]
  (log! actor-ref "was told:" msg))

(defmulti stop-receiving-messages!
  (fn [actor-ref]
    {:pre [(s/valid? ::actor-ref actor-ref)]}
    (::scope actor-ref)))

(defmethod stop-receiving-messages! :local
  [actor-ref]
  (async/close! (::signal-port actor-ref))
  (async/close! (::normal-port actor-ref)))

(defmethod stop-receiving-messages! :default
  [_actor-ref])

(defn <ask!
  [actor-ref msg]
  {:pre [(s/valid? ::actor-ref actor-ref)
         (s/valid? ::msg msg)]
   :post [(s/valid? ::port %)]}
  (let [ch (chan)
        timeout-ch (async/timeout 1000)]
    #_(tell! actor-ref (assoc msg :from (mk-reply-ref ch)))
    #_(go
      (let [[v p] (async/alts! [ch timeout-ch])]
        (if (= p timeout-ch)
          (do
            (log! actor-ref "<ask! timed out:" msg)
            nil)
          v)))))

(defn reg-watcher!
  [actor-ref watcher]
  {:pre [(s/valid? ::actor-ref actor-ref)
         (s/valid? ::actor-ref watcher)]}
  (if (deref (::dead? actor-ref))
    (tell! watcher {:from actor-ref :body ::terminated})
    (swap! (::watchers actor-ref) conj watcher)))

(defn unreg-watcher!
  [actor-ref watcher]
  {:pre [(s/valid? ::actor-ref actor-ref)
         (s/valid? ::actor-ref watcher)]}
  (swap! (::watchers actor-ref) disj watcher))

(defn reg-death!
  [actor-ref]
  {:pre [(s/valid? ::actor-ref actor-ref)]}
  (reset! (::dead? actor-ref) true)
  (doseq [watcher (first (swap-vals! (::watchers actor-ref) #{}))]
    (tell! watcher {:from actor-ref :body ::terminated}))
  (tell! (::parent actor-ref) {:signal? true :from actor-ref :body ::child-terminated}))

(declare spawn-actor!)

(defn spawn!
  [ctx actor-name behaviors props]
  (let [child-ref (local-actor-ref (::self ctx) actor-name)]
    (swap! (::children ctx) conj child-ref)
    (spawn-actor! child-ref behaviors props)))

(defn remove-child!
  [ctx child-ref]
  (log! (::self ctx) "removing child:" child-ref)
  (swap! (::children ctx) disj child-ref))

(defn <stop-all-children!
  [ctx]
  (go
    (log! (::self ctx) "stopping all children...")
    (log! (::self ctx) "all children stopped:"
          (<! (apply
               <take-all
               (map #(<ask! % {:body ::stop})
                    (deref (::children ctx)))))
          (reset! (::children ctx) #{}))
    true))

(defn- actor-context
  [self-ref behaviors props]
  {::self self-ref
   ::behaviors behaviors
   ::props props
   ::children (atom #{})})

(defn- ctx-rcv
  [ctx]
  (go 
    (let [self-ref (::self ctx)
          [msg _port] (async/alts! [(::signal-port self-ref)
                                    (::normal-port self-ref)])]
      (log! self-ref "got message:" msg)
      msg)))

(defmacro receive
  [ctx state & clauses]
  `(go
     (try
       (match [(<! (ctx-rcv ~ctx))]
              [{:body ::stop}] [::stopped ~state nil]
              [{:from sender# :body ::stop}] [::stopped ~state sender#]
              [{:from sender# :body ::child-terminated}] [::child-terminated ~state sender#]
              ~@clauses)
       (catch Exception e#
         [::exception ~state e#]))))

(declare default-behaviors)

(defn get-behavior
  [ctx id]
  (get (merge default-behaviors (::behaviors ctx)) id))

(defn- default-init-behavior
  [_ctx _props]
  [::receive nil])

(defn noop-behavior
  [ctx state]
  (receive ctx state
           [ignored-msg]
           (do
             (log! (::self ctx) "Message ignored:" ignored-msg)
             nil)))

(defn- default-cleanup-behavior
  [_ctx _state]
  [::done nil])

(defn- default-stopped-behavior
  [ctx state stop-sender]
  (go
    (let [self-ref (::self ctx)
          cleanup-behavior (get-behavior ctx ::cleanup)]
      (log! self-ref "stopping requested by:" stop-sender)
      (stop-receiving-messages! self-ref)
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
  (log! (::self ctx) "exception:" e)
  (log! (::self ctx) "actor will restart")
  (let [cleanup-behavior (get-behavior ctx ::cleanup)
        init-behavior (get-behavior ctx ::init)
        props (::props ctx)]
    (cleanup-behavior ctx state)
    [::receive (init-behavior ctx props)]))

(def ^:private default-behaviors
  {::init default-init-behavior
   ::receive noop-behavior
   ::cleanup default-cleanup-behavior
   ::stopped default-stopped-behavior
   ::child-terminated default-child-terminated-behavior
   ::exception default-exception-behavior})

(defn- run-loop!
  [ctx]
  (go-loop [behavior (get-behavior ctx ::init)
            state [(::props ctx)]]
    (let [call-result (apply behavior ctx state)
          read-result (if (chan? call-result) (<! call-result) call-result)
          _ (log! (::self ctx) "read-result:" read-result)

          [next-behavior & next-state]
          (cond 
            (nil? read-result) [behavior state]
            (fn? read-result) [read-result state]
            (vector? read-result) read-result)]
      (if (= next-behavior ::terminate-run-loop)
        (log! (::self ctx) "run loop terminated")
        (recur (if (fn? next-behavior)
                 next-behavior
                 (get-behavior ctx next-behavior))
               next-state)))))

(defn- spawn-actor!
  [self-ref behaviors props]
  (run-loop! (actor-context self-ref behaviors props))
  self-ref)

(defn- user-guard-receive-behavior
  [ctx {:keys [main-actor-ref] :as state}]
  (receive ctx state
           [{:from main-actor-ref :body ::terminated}]
           (do
             (log! (::self ctx) "Main actor is dead. Stopping the user guard...")
             [::stopped state nil])
           :else [::receive state]))

(defn- user-guard-init-behavior
  [ctx [result-ch actor-name behavior-m actor-args]]
  (log! (::self ctx) "In user subsystem init...")
  (let [main-actor-ref (spawn! ctx actor-name behavior-m actor-args)]
    (go (>! result-ch main-actor-ref)
        (async/close! result-ch))
    (reg-watcher! main-actor-ref (::self ctx))
    [user-guard-receive-behavior {:main-actor-ref main-actor-ref}]))

(defn- user-guard-cleanup-behavior
  [ctx {:keys [main-actor-ref] :as state}]
  (when main-actor-ref (unreg-watcher! main-actor-ref (::self ctx)))
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
             (log! (::self ctx) "The user guard is dead. Stopping the actor system...")
             [::stopped state nil])
           :else [::receive state]))

(defn- actor-system-init-behavior
  [ctx [result-ch actor-name behavior-m actor-args]]
  (log! (::self ctx) "In actor system init...")
  (let [user-guard-ref
        (spawn! ctx "user" user-guard [result-ch actor-name behavior-m actor-args])]
    (reg-watcher! user-guard-ref (::self ctx))
    [::receive {:user-guard-ref user-guard-ref}]))

(defn- actor-system-cleanup-behavior
  [ctx {:keys [user-guard-ref] :as state}]
  (when user-guard-ref (unreg-watcher! user-guard-ref (::self ctx)))
  [::done (assoc state :user-guard-ref nil)])

(def ^:private actor-system
  {::init actor-system-init-behavior
   ::receive actor-system-receive-behavior
   ::cleanup actor-system-cleanup-behavior})

(defn <start-system!
  [actor-name behavior-m actor-args]
  (let [result-ch (chan)]
    (spawn-actor! (local-actor-ref (bubble-ref) "system@localhost")
                  actor-system [result-ch actor-name behavior-m actor-args])
    result-ch))

(comment
  (defn my-hero-init-behavior
    [ctx _props]
    (log! (::self ctx) "In MyHero init...")
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
