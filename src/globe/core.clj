(ns globe.core
  (:require [clj-uuid :as uuid]
            [clojure.core.async :as async :refer [<! >! chan go go-loop]]
            [clojure.core.match :refer [match]]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [cognitect.anomalies :as anom]
            [globe.async :refer [<? <take-all chan? err-or unbound-buf go-safe]])
  (:import [clojure.lang Atom]))

(defonce !system-ctx (atom nil))

(defn get-system-ctx
  []
  (deref !system-ctx))

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
(s/def ::subj keyword?)
(s/def ::body some?)
(s/def ::from ::actor-ref)
(s/def ::signal? true?)
(s/def ::msg (s/keys :req-un [::subj]
                     :opt-un [::body ::from ::signal?]))

(defn get-path
  ([actor-ref]
   (get-path actor-ref nil))
  ([actor-ref postfix]
   {:pre [(or (nil? actor-ref)
              (s/valid? ::actor-ref actor-ref))
          (s/valid? (s/nilable string?) postfix)]}
   (if actor-ref
     (case (::scope actor-ref)
       :local (recur (::parent actor-ref)
                     (str "/" (::id actor-ref) postfix))
       :bubble (str "globe:/" postfix))
     postfix)))

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

(defn reply!
  [msg reply-body]
  (tell! (:from msg) {:subj ::reply :body reply-body}))

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
    (tell! watcher {:from actor-ref :subj ::terminated})
    (swap! (::watchers actor-ref) conj watcher)))

(defn unreg-watcher!
  [actor-ref watcher]
  {:pre [(s/valid? ::actor-ref actor-ref)
         (s/valid? ::actor-ref watcher)]}
  (swap! (::watchers actor-ref) disj watcher))

(defn reg-death!
  [actor-ref]
  {:pre [(s/valid? ::actor-ref actor-ref)]}
  (tell! (::parent actor-ref) {:signal? true :from actor-ref :subj ::child-terminated})
  (doseq [watcher (first (swap-vals! (::watchers actor-ref) #{}))]
    (tell! watcher {:from actor-ref :subj ::terminated})))

(defn drain-messages
  [self-ref port-name port]
  (go-loop [msg-count 0]
    (if-let [msg (<! port)]
      (do
        (log! self-ref (str "message drained on " port-name ":") msg)
        (recur (inc msg-count)))
      (when (pos? msg-count)
        (log! self-ref (str "messages drained on " port-name " total:") msg-count)))))

(declare run-loop!)
(declare <ask!)

(defn- actor-context
  [parent self-ref actor-fn]
  {::parent parent
   ::self self-ref
   ::actor-fn actor-fn
   ::children (atom {})
   ::suspended? (atom false)
   ::behavior-id (atom ::running)
   ::handlers (atom {})})

(defn spawn!
  [ctx actor-name actor-fn & {:keys [on-failure]
                              :or {on-failure ::restart}}]
  (let [child-ref (local-actor-ref (::self ctx) actor-name)
        child-ctx (actor-context ctx child-ref actor-fn)]
    (swap! (::children ctx) assoc actor-name {:context child-ctx
                                              :on-failure on-failure})
    (run-loop! child-ctx)
    child-ref))

(defn- behavior-id
  [ctx]
  (deref (::behavior-id ctx)))

(defn- behave-as!
  [ctx behavior-id]
  (reset! (::behavior-id ctx) behavior-id))

(defn remove-child!
  [ctx child-ref]
  (let [self-ref (::self ctx)
        !children (::children ctx)]
    (log! self-ref "removing child:" (get-path child-ref))
    (swap! !children dissoc (::id child-ref))
    (when-not (seq @!children)
      (tell! self-ref {:signal? true :subj ::children-stopped}))))

(defn has-children?
  [ctx]
  (seq (deref (::children ctx))))

(defn children-refs
  [ctx]
  (map (comp ::self :context) (vals (deref (::children ctx)))))

(defn get-child
  [ctx id]
  (let [children (deref (::children ctx))]
    (children id)))

(defn- suspend!
  [ctx]
  (reset! (::suspended? ctx) true))

(defn- resume!
  [ctx]
  (reset! (::suspended? ctx) false))

(defn- suspended?
  [ctx]
  (deref (::suspended? ctx)))

(defn- active-ports
  [ctx]
  (let [self-ref (::self ctx)
        signal-port (::signal-port self-ref)
        normal-port (::normal-port self-ref)]
    (if (suspended? ctx)
      [signal-port]
      [signal-port normal-port])))

(defn- tell-children!
  [ctx msg & {:keys [on-no-children]
              :or {on-no-children (fn [])}}]
  (let [child-refs (children-refs ctx)]
    (if (seq child-refs)
      (doseq [child-ref child-refs]
        (tell! child-ref msg))
      (on-no-children))))

(defn tell-children-to-stop
  [ctx]
  (log! (::self ctx) "telling all children to stop...")
  (tell-children! ctx {:signal? true :subj ::poison-pill}
                  :on-no-children
                  #(tell! (::self ctx) {:signal? true :subj ::children-stopped})))

(defn tell-children-to-restart
  [ctx]
  (log! (::self ctx) "telling all children to restart...")
  (tell-children! ctx {:signal? true :subj ::restart}))

(defn- handle-poison-pill
  [ctx]
  (suspend! ctx)
  (behave-as! ctx ::stopping)
  (tell-children-to-stop ctx))

(defn- terminate!
  [ctx]
  (let [self-ref (::self ctx)
        signal-port (::signal-port self-ref)
        normal-port (::normal-port self-ref)]
    (stop-receiving-messages! self-ref)
    (go
      (<! (drain-messages self-ref "signal" signal-port))
      (<! (drain-messages self-ref "normal" normal-port))
      (reg-death! self-ref)
      (log! self-ref "terminated"))))

(defn- handle-anomaly
  [ctx anomaly]
  (let [self-ref (::self ctx)]
    (log! self-ref "handling anomaly:" anomaly)
    (suspend! ctx)
    (behave-as! ctx ::awaiting-supervisor-decision)
    (tell! (::parent self-ref) {:signal? true :from self-ref :subj ::failure :body anomaly})))

(defn- supervise-child
  [ctx child-ref anomaly]
  (loop [on-failure (:on-failure (get-child ctx (::id child-ref)) ::restart)]
    (case on-failure
      ::resume (tell! child-ref {:signal? true :from (::self ctx) :subj ::resume})
      ::restart (tell! child-ref {:signal? true :from (::self ctx) :subj ::restart})
      ::restart-all (tell-children-to-restart ctx)
      ::escalate (handle-anomaly ctx anomaly)
      ::stop (tell! child-ref {:signal? true :subj ::poison-pill})
      (recur (on-failure child-ref anomaly)))))

(defn- handle-supervised-resume
  [ctx]
  (behave-as! ctx ::running)
  (resume! ctx))

(defn- handle-supervised-restart
  [ctx]
  (behave-as! ctx ::restarting)
  (tell-children-to-stop ctx))

(declare init-actor!)
(declare cleanup-actor!)

(defn- restart-actor!
  [ctx]
  (cleanup-actor! ctx)
  (behave-as! ctx ::running)
  (init-actor! ctx)
  (resume! ctx))

(defn receive!
  [ctx msg]
  (let [self-ref (::self ctx)
        parent-ref (::parent self-ref)]
    (match [(behavior-id ctx) msg]
           [::awaiting-supervisor-decision {:subj ::resume :from parent-ref}]
           (handle-supervised-resume ctx)
           [::awaiting-supervisor-decision {:subj ::restart :from parent-ref}]
           (handle-supervised-restart ctx)
           [::running {:subj ::poison-pill}]
           (handle-poison-pill ctx)
           [::running {:subj ::failure :from child-ref :body anomaly}]
           (supervise-child ctx child-ref anomaly)
           [_ {:subj ::child-terminated :from child-ref}]
           (remove-child! ctx child-ref)
           [::stopping {:subj ::children-stopped}]
           (terminate! ctx)
           [::restarting {:subj ::children-stopped}]
           (restart-actor! ctx)
           :else
           (log! (::self ctx) "Message ignored:" msg))))

(defn- default-handlers
  [ctx]
  {:receive (fn [msg] (receive! ctx msg))
   :cleanup (fn [])})

(defn- extract-handlers-from-init-result
  [ctx init-result]
  (merge (default-handlers ctx)
         (if (map? init-result)
           init-result
           {:receive init-result})))

(defn- init-actor!
  [ctx]
  (let [actor-fn (::actor-fn ctx)
        receive-or-m (actor-fn ctx)
        handlers (extract-handlers-from-init-result ctx receive-or-m)]
    (reset! (::handlers ctx) handlers)))

(defn- cleanup-actor!
  [ctx]
  (when-let [cleanup (:cleanup (deref (::handlers ctx)))]
    (cleanup)))

(defn- trap-stopped-kw
  [ctx ch]
  (go
    (let [result (err-or (<? ch))]
      (if (= ::stopped result)
        (handle-poison-pill ctx)
        result))))

(defn- ensure-chan
  [result]
  (if (chan? result)
    result
    (if result
      (async/to-chan! [result])
      (async/to-chan! []))))

(defn- run-msg-handler
  [h msg]
  (ensure-chan (err-or (h msg))))

(defn- trap-anomalies
  [ctx ch]
  (go
    (let [v (err-or (<? ch))]
      (if (::anom/category v)
        (handle-anomaly ctx v)
        v))))

(defn- handle-message!
  [ctx msg]
  (go
    (when msg
      (let [self-ref (::self ctx)
            receive (:receive (deref (::handlers ctx)))]
        (log! self-ref "got msg:" msg)
        (<! (->> msg
                 (run-msg-handler receive)
                 (trap-stopped-kw ctx)
                 (trap-anomalies ctx)))
        (log! self-ref "done processing msg, recurring..."))
      true)))

(defn- run-loop!
  [ctx]
  (init-actor! ctx)
  (go-loop []
    (let [ports (active-ports ctx)
          [msg _port] (async/alts! ports :priority true)]
      (when (<! (handle-message! ctx msg))
        (recur)))))

(defn- user-guard
  [props ctx]
  (log! (::self ctx) "Initialising...")
  (let [{:keys [result-ch actor-name actor-fn]} props
        main-actor-ref (spawn! ctx actor-name actor-fn)]
    (go (>! result-ch main-actor-ref)
        (async/close! result-ch))
    (reg-watcher! main-actor-ref (::self ctx))

    {:cleanup
     (fn []
       (unreg-watcher! main-actor-ref (::self ctx)))

     :receive
     (fn [msg]
       (match [msg]
              [{:from main-actor-ref :subj ::terminated}]
              (do
                (log! (::self ctx) "Main actor is dead. Stopping the user guard...")
                ::stopped)
              :else (receive! ctx msg)))}))

(defn- temp-guard
  [ctx]
  (log! (::self ctx) "Initialising...")
  (partial receive! ctx))

(defn- actor-system
  [props ctx]
  (log! (::self ctx) "Initialising...")
  (spawn! ctx "temp" temp-guard)
  (let [user-guard-ref (spawn! ctx "user" (partial user-guard props))]
    (reg-watcher! user-guard-ref (::self ctx))

    {:cleanup
     (fn []
       (unreg-watcher! user-guard-ref (::self ctx)))

     :receive
     (fn [msg]
       (match [msg]
              [{:from user-guard-ref :subj ::terminated}]
              (do
                (log! (::self ctx) "The user guard is dead. Stopping the actor system...")
                ::stopped)
              :else (receive! ctx msg)))}))

(defn <start-system!
  [actor-name actor-fn]
  (let [result-ch (chan)
        system-ref (local-actor-ref (bubble-ref) "system@localhost")
        system-props {:result-ch result-ch
                      :actor-name actor-name
                      :actor-fn actor-fn}
        system-ctx (actor-context nil system-ref (partial actor-system system-props))]
    (reset! !system-ctx system-ctx)
    (run-loop! system-ctx)
    result-ch))

(defn ask-actor
  [{:keys [target msg reply-ch]} ctx]
  (tell! target (assoc msg :from (::self ctx)))

  (fn [msg]
    (match [msg]
           [{:subj ::reply :body body}]
           (do
             (go (>! reply-ch body)
                 (async/close! reply-ch))
             ::stopped)
           :else (receive! ctx msg))))

(defn <ask!
  ([actor-ref msg]
   (<ask! actor-ref 5000 msg))
  ([actor-ref timeout-ms msg]
   {:pre [(s/valid? ::actor-ref actor-ref)
          (s/valid? ::msg msg)]
    :post [(s/valid? ::port %)]}
   (let [temp-ctx (:context (get-child (get-system-ctx) "temp"))
         ch (chan)
         timeout-ch (async/timeout timeout-ms)]
     (spawn! temp-ctx (uuid/v4) (partial ask-actor
                                         {:target actor-ref
                                          :msg msg
                                          :reply-ch ch}))
     (go
       (let [[v p] (async/alts! [ch timeout-ch])]
         (if (= p timeout-ch)
           (do
             (log! actor-ref "<ask! timed out:" msg)
             (async/close! ch)
             :timeout)
           v))))))

(comment
  (defn my-hero
    [ctx]
    (log! (::self ctx) "Initialising...")
    (partial receive! ctx))

  (defn greeter
    [greeting ctx]
    (log! (::self ctx) "Initialising...")
    (let [state (atom 0)]
      (spawn! ctx "my-hero" my-hero)

      (fn [msg]
        (match [msg]
               [{:subj :greet :body who}]
               (println (format "%s %s!" greeting who))
               [{:subj :wassup?}]
               (reply! msg "WASSUP!!!")
               [{:subj :throw}]
               (throw (ex-info "Something went wrong!" {:reason :requested}))
               [{:subj :inc}]
               (let [x (swap! state inc)]
                 (log! (::self ctx) "X:" x))
               :else (receive! ctx msg)))))
  
  (def !main-actor-ref (atom nil))

  (go (reset! !main-actor-ref
              (<! (<start-system! "greeter" (partial greeter "Hello")))))
  
  (tell! @!main-actor-ref {:subj :greet :body "Andrey"})
  (tell! @!main-actor-ref {:subj :inc})
  (go (println "reply:"
               (<! (<ask! @!main-actor-ref {:subj :wassup?}))))
  (tell! @!main-actor-ref {:subj ::poison-pill})
  (tell! @!main-actor-ref {:subj :throw})

  ;; helpers

  (tap> @!main-actor-ref)
  
  (ns-unmap (find-ns 'globe.core) 'log)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'eploko.globe5/")
          (map str
               (vals
                (ns-map (find-ns 'eploko.globe5)))))
  ,)
