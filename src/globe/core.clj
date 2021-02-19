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
  [ctx actor-name actor-fn]
  (let [child-ref (local-actor-ref (::self ctx) actor-name)
        child-ctx (actor-context ctx child-ref actor-fn)]
    (swap! (::children ctx) assoc actor-name child-ctx)
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
    (when (and (= ::stopping (behavior-id ctx))
               (not (seq @!children)))
      (tell! self-ref {:signal? true :subj ::children-stopped}))))

(defn has-children?
  [ctx]
  (seq (deref (::children ctx))))

(defn children-refs
  [ctx]
  (map ::self (vals (deref (::children ctx)))))

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

(defn tell-children-to-stop
  [ctx]
  (log! (::self ctx) "telling all children to stop...")
  (let [child-refs (children-refs ctx)]
    (if (seq child-refs)
      (doseq [child-ref child-refs]
        (tell! child-ref {:signal? true :subj ::poison-pill}))
      (tell! (::self ctx) {:signal? true :subj ::children-stopped}))))

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

(defn- handle-children-stopped
  [ctx]
  (terminate! ctx))

(defn receive!
  [ctx msg]
  (match [(behavior-id ctx) msg]
         [::running {:subj ::poison-pill}] (handle-poison-pill ctx)
         [_ {:subj ::child-terminated :from child-ref}] (remove-child! ctx child-ref)
         [::stopping {:subj ::children-stopped}] (handle-children-stopped ctx)
         :else (log! (::self ctx) "Message ignored:" msg)))

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

(defn- process-received-result
  [ctx result]
  (go-loop [result result]
    (cond
      (chan? result) (recur (<! result))
      (= ::stopped result) (handle-poison-pill ctx)
      :else result)))

(defn- run-loop!
  [ctx]
  (init-actor! ctx)
  (let [self-ref (::self ctx)]
    (go-loop []
      (let [receive (:receive (deref (::handlers ctx)))
            ports (active-ports ctx)
            [msg _port] (async/alts! ports :priority true)]
        (when msg
          (log! self-ref "got msg:" msg)
          (<! (process-received-result ctx (receive msg)))
          (log! self-ref "done processing msg, recurring...")
          (recur))))))

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

(defn get-system-ctx
  []
  (deref !system-ctx))

(defn find-child
  [ctx id]
  (let [children (deref (::children ctx))]
    (children id)))

(defn ask-actor
  [{:keys [target msg reply-ch]} ctx]
  (tell! target (assoc msg :from (::self ctx)))

  (fn [msg]
    (match [msg]
           [{:from target :body body}]
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
   (let [temp-ctx (find-child (get-system-ctx) "temp")
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
    (let [self (::self ctx)
          state (atom 0)]
      (spawn! ctx "my-hero" my-hero)

      (fn [msg]
        (match [msg]
               [{:subj :greet :body who}]
               (println (format "%s %s!" greeting who))
               [{:subj :wassup? :from sender}]
               (tell! sender {:from self :subj :reply :body "WASSUP!!!"})
               :else (receive! ctx msg)))))
  
  (def !main-actor-ref (atom nil))

  (go (reset! !main-actor-ref
              (<! (<start-system! "greeter" (partial greeter "Hello")))))
  
  (tell! @!main-actor-ref {:subj :greet :body "Andrey"})
  (go (println "reply:"
               (<! (<ask! @!main-actor-ref {:subj :wassup?}))))
  (tell! @!main-actor-ref {:subj ::poison-pill})

  ;; helpers

  (tap> @!main-actor-ref)
  
  (reset! !behaviors {})

  (ns-unmap (find-ns 'eploko.globe5) 'log)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'eploko.globe5/")
          (map str
               (vals
                (ns-map (find-ns 'eploko.globe5)))))
  ,)
