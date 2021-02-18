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
  (let [port-id (if (:signal? msg) ::signal-port ::normal-port)
        port (actor-ref port-id)
        dead? (deref (::dead? actor-ref))]
    (if (and (= port-id ::normal-port)
             dead?)
      (log! actor-ref "port dead, not listening to:" msg)
      (go (>! port msg)))))

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
  (doseq [watcher (first (swap-vals! (::watchers actor-ref) #{}))]
    (tell! watcher {:from actor-ref :body ::terminated}))
  (tell! (::parent actor-ref) {:signal? true :from actor-ref :body ::child-terminated}))

(declare run-loop!)
(declare <ask!)

(defn- actor-context
  [parent self-ref behaviors props]
  (let [ctx {::parent parent
             ::self self-ref
             ::behaviors behaviors
             ::props props
             ::state (atom props)
             ::children (atom {})
             ::signalling-behavior (atom :running)}]
    (add-watch (::signalling-behavior ctx) ::signalling-behavior
               (fn [_k _r _os ns]
                 (log! (::self ctx) ":signalling-behavior changed to:" ns)))
    ctx))

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

(declare default-behaviors)

(defn get-behavior
  [ctx id]
  (get (merge default-behaviors (::behaviors ctx)) id))

(defn- default-init-behavior
  [_ctx _state]
  ::receive)

(defn default-receive-behavior
  [ctx _state msg]
  (match [msg]
         [ignored-msg]
         (do
           (log! (::self ctx) "Message ignored:" ignored-msg)
           ::receive)))

(defn- default-cleanup-behavior
  [_ctx _state]
  ::done)

(defn- default-stopping-children-behavior
  [ctx _state msg opts]
  (go
    (let [receive-result
          (<! (match [msg]
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
   ::receive default-receive-behavior
   ::cleanup default-cleanup-behavior
   ::stopped default-stopped-behavior
   ::stopping-children default-stopping-children-behavior
   ::children-stopped default-children-stopped-behavior
   ::exception default-exception-behavior})

(defn resolve-behavior
  [ctx behavior]
  (if (fn? behavior)
    behavior
    (if-let [resolved-behavior (get-behavior ctx behavior)]
      resolved-behavior
      (throw (ex-info (str "Unknown behavior " behavior " for: "
                           (get-path (::self ctx)))
                      {:fatal true})))))

(defn run-behavior
  [behavior ctx & args]
  (go
    (let [call-result (apply behavior ctx (::state ctx) args)
          next-behavior (if (chan? call-result)
                          (<! call-result)
                          call-result)]
      (resolve-behavior ctx next-behavior))))

(defn replace-same-behavior
  [target replacement]
  (if (= ::same target)
    replacement
    target))

(defn- run-port-loop!
  [loop-name self-ref port handler]
  (go-loop [next-handler handler]
    (if-let [msg (<! port)]
      (do
        (log! self-ref (str loop-name " msg:" msg))
        (recur (<! (next-handler msg))))
      (log! self-ref (str "loop terminated: " loop-name)))))

(defn has-children?
  [ctx]
  (seq (deref (::children ctx))))

(defn children-refs
  [ctx]
  (map ::self (vals (deref (::children ctx)))))

(declare handle-signal)
(declare terminate!)

(defn drain-messages
  [self-ref port-name port]
  (log! self-ref (str "draining messages on " port-name "..."))
  (go-loop [msg-count 0]
    (if-let [msg (<! port)]
      (do
        (log! self-ref (str "message drained on " port-name ":") msg)
        (recur (inc msg-count)))
      (log! self-ref (str "messages drained on " port-name "total:") msg-count))))

(defmulti handle-child-terminated-signal
  (fn [ctx _child-ref]
    (deref (::signalling-behavior ctx))))

(defmethod handle-child-terminated-signal :default
  [ctx child-ref]
  (remove-child! ctx child-ref))

(defmethod handle-child-terminated-signal :stopping
  [ctx child-ref]
  (go 
    (remove-child! ctx child-ref)
    (when-not (has-children? ctx)
      (<! (terminate! ctx)))))

(defn- terminate!
  [ctx]
  (go
    (let [self-ref (::self ctx)
          signal-port (::signal-port self-ref)]
      (log! self-ref "terminating...")
      (async/close! signal-port)
      (<! (drain-messages self-ref "signal" signal-port))
      (reg-death! self-ref))))

(defn tell-children-to-stop
  [ctx]
  {:pre [(has-children? ctx)]}
  (log! (::self ctx) "telling all children to stop...")
  (doseq [child-ref (children-refs ctx)]
    (tell! child-ref {:body ::poison-pill})))

(defn- handle-stopped-signal
  [ctx]
  (go
    (reset! (::signalling-behavior ctx) :stopping)
    (log! (::self ctx) "::signalling-behavior" (deref (::signalling-behavior ctx)))
    (if (has-children? ctx)
      (tell-children-to-stop ctx)
      (do
        (log! (::self ctx) "has no children, alright")
        (<! (terminate! ctx))))))

(defn- handle-signal
  [ctx msg]
  (go
    (let [self-ref (::self ctx)
          result (match [msg]
                        [{:from self-ref :body ::stopped}]
                        (handle-stopped-signal ctx)
                        [{:from child-ref :body ::child-terminated}]
                        (handle-child-terminated-signal ctx child-ref))]
      (when (chan? result)
        (<! result))
      (partial handle-signal ctx))))

(defn handle-poison-pill-message
  [ctx]
  (go
    (let [self-ref (::self ctx)
          normal-port (::normal-port self-ref)]
      (log! self-ref "got ::poison-pill")
      (reset! (::dead? self-ref) true)
      (async/close! normal-port)
      (<! (drain-messages self-ref "normal" normal-port))
      (tell! self-ref {:signal? true :from self-ref :body ::stopped}))))

(defn- handle-message
  [behavior ctx msg]
  (go
    (match [msg]
           [{:body ::poison-pill}]
           (<! (handle-poison-pill-message ctx))
           :else
           (partial handle-message
                    (<! (run-behavior behavior ctx msg))
                    ctx))))

(defn- run-loop!
  [ctx]
  (go
    (let [self-ref (::self ctx)
          init-behavior (resolve-behavior ctx ::init)
          first-behavior (<! (run-behavior init-behavior ctx))]
      (run-port-loop! "signal" self-ref
                      (::signal-port self-ref)
                      (partial handle-signal ctx))
      (run-port-loop! "normal" self-ref
                      (::normal-port self-ref)
                      (partial handle-message first-behavior ctx)))))

(defn- user-guard-receive-behavior
  [ctx state msg]
  (let [main-actor-ref (:main-actor-ref @state)]
    (match [msg]
           [{:from main-actor-ref :body ::terminated}]
           (do
             (log! (::self ctx) "Main actor is dead. Stopping the user guard...")
             ::stopped)
           :else ::receive)))

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
  [ctx state msg]
  (let [user-guard-ref (:user-guard-ref @state)]
    (match [msg]
           [{:from user-guard-ref :body ::terminated}]
           (do
             (log! (::self ctx) "The user guard is dead. Stopping the actor system...")
             ::stopped)
           :else ::receive)))

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
  [ctx state msg]
  (let [{:keys [target reply-ch]} @state]
    (match [msg]
           [{:from target :body body}]
           (do
             (go (>! reply-ch body)
                 (async/close! reply-ch))
             ::stopped)
           :else ::receive)))

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
    [ctx state msg]
    (let [greeting (:greeting @state)]
      (match [msg]
             [{:body [:greet who]}]
             (do
               (println (format "%s %s!" greeting who))
               ::receive)
             :else ::receive)))

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
  (tell! @!main-actor-ref {:body ::poison-pill})

  (tap> @!main-actor-ref)

  (ns-unmap (find-ns 'eploko.globe5) 'log)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'eploko.globe5/")
          (map str
               (vals
                (ns-map (find-ns 'eploko.globe5)))))
  ,)
