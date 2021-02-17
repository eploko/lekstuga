(ns globe.core
  (:require [clj-uuid :as uuid]
            [clojure.core.async :as async :refer [<! >! chan go go-loop]]
            [clojure.core.match :refer [match]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [globe.async :refer [<take-all chan? unbound-buf]])
  (:import [clojure.lang Atom]))

(defonce !system-ctx (atom nil))

(defn atom?
  [x]
  (instance? Atom x))

(s/def ::non-empty-string (s/and string? #(> (count %) 0)))
(s/def ::uuid uuid?)
(s/def ::port chan?)
(s/def ::signal-port ::port)
(s/def ::normal-port ::port)
(s/def ::scope keyword?)
(s/def ::id (s/or :string ::non-empty-string
                  :uuid ::uuid))
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
    {:pre [(or (nil? actor-ref)
               (s/valid? ::actor-ref actor-ref))]}
    (if (nil? actor-ref)
      :nil
      (::scope actor-ref))))

(defmethod get-path :nil
  [_]
  nil)

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

(declare run-loop!)
(declare <ask!)

(defn- actor-context
  [parent self-ref behaviors props]
  {::parent parent
   ::self self-ref
   ::behaviors behaviors
   ::props props
   ::state (atom props)
   ::children (atom {})})

(defn spawn!
  [ctx actor-name behaviors props]
  (let [child-ref (local-actor-ref (::self ctx) actor-name)
        child-ctx (actor-context ctx child-ref behaviors props)]
    (swap! (::children ctx) assoc (::id child-ref) child-ctx)
    (run-loop! child-ctx)
    child-ref))

(defn remove-child!
  [ctx child-ref]
  (log! (::self ctx) "removing child:" (get-path child-ref))
  (swap! (::children ctx) dissoc (::id child-ref)))

(defn- ctx-rcv
  [ctx]
  (go 
    (let [self-ref (::self ctx)
          [msg _port] (async/alts! [(::signal-port self-ref)
                                    (::normal-port self-ref)])]
      (log! self-ref
            (if (:signal? msg) "signal:" "message:")
            (:body msg) "from:" (get-path (:from msg)))
      msg)))

(defmacro receive
  [ctx & clauses]
  `(go
     (try
       (match [(<! (ctx-rcv ~ctx))]
              [{:from sender# :body ::stop}] [::stopped {:requested-by sender#}]
              [{:body ::stop}] ::stopped
              [{:from sender# :body ::child-terminated}]
              (do (remove-child! ~ctx sender#)
                  ::same)
              ~@clauses)
       (catch Exception e#
         [::exception e#]))))

(declare default-behaviors)

(defn get-behavior
  [ctx id]
  (get (merge default-behaviors (::behaviors ctx)) id))

(defn- default-init-behavior
  [_ctx _state]
  ::receive)

(defn noop-behavior
  [ctx _state]
  (receive ctx
           [ignored-msg]
           (do
             (log! (::self ctx) "Message ignored:" ignored-msg)
             ::same)))

(defn- default-cleanup-behavior
  [_ctx _state]
  ::done)

(defn- default-stopping-children-behavior
  [ctx _state opts]
  (go
    (let [receive-result
          (<! (receive ctx
                       [{:body ::all-children-stopped}] [::children-stopped opts]
                       :else ::same))]
      (if (= ::same receive-result)
        (do
          (when (and 
                 (not (seq? (deref (::children ctx)))))
            (tell! (::self ctx) {:signal? true :body ::all-children-stopped}))
          [receive-result opts])
        receive-result))))

(defn- default-children-stopped-behavior
  [ctx _state {:keys [requested-by]}]
  (let [self-ref (::self ctx)]
    (stop-receiving-messages! self-ref)
    (log! self-ref "actor stopped")
    (reg-death! self-ref)
    (when requested-by
      (tell! requested-by {:from self-ref :body ::stopped}))
    ::terminate-run-loop))

(defn- default-stopped-behavior
  ([ctx state]
   (default-stopped-behavior ctx state {}))
  ([ctx state {:as opts
               :keys [requested-by]}]
   (let [self-ref (::self ctx)
         cleanup-behavior (get-behavior ctx ::cleanup)]
     (when requested-by
       (log! self-ref "stopping requested by:" requested-by))
     (cleanup-behavior ctx state)
     (let [children-refs (map ::self (vals (deref (::children ctx))))]
       (if (seq children-refs)
         (do
           (log! (::self ctx) "telling all children to stop...")
           (doseq [child-ref children-refs]
             (tell! child-ref {:signal? true :body ::stop}))
           [::stopping-children opts])
         [::children-stopped opts])))))

(defn- default-exception-behavior
  [ctx state e]
  (log! (::self ctx) "exception:" e)
  (log! (::self ctx) "actor will restart")
  (let [cleanup-behavior (get-behavior ctx ::cleanup)
        init-behavior (get-behavior ctx ::init)
        props (::props ctx)]
    (cleanup-behavior ctx state)
    (reset! state props)
    (init-behavior ctx state)))

(def ^:private default-behaviors
  {::init default-init-behavior
   ::receive noop-behavior
   ::cleanup default-cleanup-behavior
   ::stopped default-stopped-behavior
   ::stopping-children default-stopping-children-behavior
   ::children-stopped default-children-stopped-behavior
   ::exception default-exception-behavior})

(defn resolve-behavior
  [ctx behavior]
  (if (fn? behavior)
    behavior
    (get-behavior ctx behavior)))

(defn replace-same-behavior
  [target replacement]
  (if (= ::same target)
    replacement
    target))

(defn- run-loop!
  [ctx]
  (let [state (::state ctx)]
    (go-loop [behavior ::init
              behavior-args []]
      (log! (::self ctx) "behavior:" behavior "args:" behavior-args)
      (if-let [resolved-behavior (resolve-behavior ctx behavior)]
        (let [call-result (apply resolved-behavior ctx state behavior-args)
              read-result (if (chan? call-result) (<! call-result) call-result)]
          (cond
            (= ::terminate-run-loop read-result) (log! (::self ctx) "run loop terminated")
            (= ::same read-result) (recur behavior behavior-args)
            (vector? read-result) (recur (replace-same-behavior (first read-result) behavior)
                                         (rest read-result))
            :else (recur read-result [])))
        (throw (ex-info (str "Invalid behavior: " behavior)
                        {:fatal true}))))))

(defn- user-guard-receive-behavior
  [ctx state]
  (let [main-actor-ref (:main-actor-ref @state)]
    (receive ctx
             [{:from main-actor-ref :body ::terminated}]
             (do
               (log! (::self ctx) "Main actor is dead. Stopping the user guard...")
               ::stopped)
             :else ::same)))

(defn- user-guard-init-behavior
  [ctx state]
  (log! (::self ctx) "In user subsystem init...")
  (let [{:keys [result-ch actor-name behaviors props]} @state
        main-actor-ref (spawn! ctx actor-name behaviors props)]
    (go (>! result-ch main-actor-ref)
        (async/close! result-ch))
    (swap! state assoc :main-actor-ref main-actor-ref)
    (reg-watcher! main-actor-ref (::self ctx))
    ::receive))

(defn- user-guard-cleanup-behavior
  [ctx state]
  (when-let [main-actor-ref (:main-actor-ref @state)]
    (unreg-watcher! main-actor-ref (::self ctx))
    (swap! state dissoc :main-actor-ref))
  ::done)

(def ^:private user-guard
  {::init user-guard-init-behavior
   ::receive user-guard-receive-behavior
   ::cleanup user-guard-cleanup-behavior})

(def ^:private temp-guard {})

(defn- actor-system-receive-behavior
  [ctx state]
  (let [user-guard-ref (:user-guard-ref @state)]
    (receive ctx
             [{:from user-guard-ref :body ::terminated}]
             (do
               (log! (::self ctx) "The user guard is dead. Stopping the actor system...")
               ::stopped)
             :else ::same)))

(defn- actor-system-init-behavior
  [ctx state]
  (log! (::self ctx) "In actor system init...")
  (let [{:keys [result-ch actor-name behaviors props]} @state
        user-guard-props {:result-ch result-ch
                          :actor-name actor-name
                          :behaviors behaviors
                          :props props}
        
        user-guard-ref
        (spawn! ctx "user" user-guard user-guard-props)]
    (spawn! ctx "temp" temp-guard nil)
    (reg-watcher! user-guard-ref (::self ctx))
    (swap! state assoc :user-guard-ref user-guard-ref)
    ::receive))

(defn- actor-system-cleanup-behavior
  [ctx state]
  (when-let [user-guard-ref (:user-guard-ref @state)]
    (unreg-watcher! user-guard-ref (::self ctx))
    (swap! state dissoc :user-guard-ref))
  ::done)

(def ^:private actor-system
  {::init actor-system-init-behavior
   ::receive actor-system-receive-behavior
   ::cleanup actor-system-cleanup-behavior})

(defn <start-system!
  [actor-name behaviors props]
  (let [result-ch (chan)
        system-ref (local-actor-ref (bubble-ref) "system@localhost")
        system-props {:result-ch result-ch
                      :actor-name actor-name
                      :behaviors behaviors
                      :props props}
        system-ctx (actor-context nil system-ref actor-system system-props)]
    (reset! !system-ctx system-ctx)
    (run-loop! system-ctx)
    result-ch))

(defn get-system-ctx
  []
  (deref !system-ctx))

(defn find-child
  [ctx id]
  (let [children (deref (::children ctx))]
    (children id)))

(defn ask-actor-init-behavior
  [ctx state]
  (let [{:keys [target msg]} @state]
    (tell! target (assoc msg :from (::self ctx)))
    ::receive))

(defn ask-actor-receive-behavior
  [ctx state]
  (let [{:keys [target reply-ch]} @state]
    (receive ctx
             [{:from target :body body}]
             (do
               (go (>! reply-ch body)
                   (async/close! reply-ch))
               ::stopped)
             :else ::same)))

(def ask-actor-behaviors
  {::init ask-actor-init-behavior
   ::receive ask-actor-receive-behavior})

(defn <ask!
  ([actor-ref msg]
   (<ask! actor-ref 5000 msg))
  ([actor-ref timeout-ms msg]
   {:pre [(s/valid? ::actor-ref actor-ref)
          (s/valid? ::msg msg)]
    :post [(s/valid? ::port %)]}
   (let [temp-ctx (find-child (get-system-ctx) "temp")
         ch (chan)
         timeout-ch (async/timeout timeout-ms)]
     (spawn! temp-ctx (uuid/v4) ask-actor-behaviors
             {:target actor-ref
              :msg msg
              :reply-ch ch})
     (go
       (let [[v p] (async/alts! [ch timeout-ch])]
         (if (= p timeout-ch)
           (do
             (log! actor-ref "<ask! timed out:" msg)
             (async/close! ch)
             :timeout)
           v))))))

(comment
  (defn my-hero-init-behavior
    [ctx _state]
    (log! (::self ctx) "In MyHero init...")
    ::receive)
  
  (def my-hero
    {::init my-hero-init-behavior})

  (defn greeter-receive-behavior
    [ctx state]
    (let [greeting (:greeting @state)]
      (receive ctx
               [{:body [:greet who]}]
               (do
                 (println (format "%s %s!" greeting who))
                 ::same)
               :else ::same)))

  (defn greeter-init-behavior
    [ctx state]
    (spawn! ctx "my-hero" my-hero [])
    (swap! state assoc :x 0)
    ::receive)

  (def greeter
    {::init greeter-init-behavior
     ::receive greeter-receive-behavior})

  (def !main-actor-ref (atom nil))

  (go (reset! !main-actor-ref
              (<! (<start-system! "greeter" greeter {:greeting "Hello"}))))
  
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
