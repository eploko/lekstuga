(ns globe.cell
  (:require
   [clojure.core.async :as async :refer [<!]]
   [clojure.core.match :refer [match]]
   [cognitect.anomalies :as anom]
   [globe.api :as api]
   [globe.async :as gasync :refer [chan? err-or]]
   [globe.context :as context]
   [globe.logger :as logger]
   [globe.msg :as msg]
   [globe.uris :as uris]))

(defn- tell-children!
  [cell msg & {:keys [on-no-children]
               :or {on-no-children (fn [])}}]
  (let [children-refs (map :ref (vals (deref (:!children cell))))]
    (if (seq children-refs)
      (doseq [child-ref children-refs]
        (api/tell! child-ref msg))
      (on-no-children))))

(defn- tell-children-to-stop
  [cell]
  (logger/log! (api/self cell) "Telling all children to stop...")
  (tell-children! cell (msg/make-signal :globe/poison-pill)
                  :on-no-children
                  #(api/tell! (api/self cell) (msg/make-signal ::children-stopped))))

(defn- tell-children-to-restart
  [cell]
  (logger/log! (api/self cell) "Telling all children to restart...")
  (tell-children! cell (msg/make-signal :globe/restart)))

(defn- handle-poison-pill
  [cell]
  (logger/log! (api/self cell) "Received a poison pill!")
  (api/suspend! cell)
  (api/switch-to-mode! cell ::stopping)
  (tell-children-to-stop cell))

(defn- handle-anomaly
  [cell _msg anomaly]
  (logger/log! (api/self cell) "Handling anomaly:" anomaly)
  (api/suspend! cell)
  (api/switch-to-mode! cell ::awaiting-supervisor-decision)
  (api/tell! (api/supervisor cell)
             (-> (msg/make-signal :globe/failure anomaly)
                 (msg/from (api/self cell)))))

(defn- supervise-child
  [cell child-ref anomaly]
  (let [on-failure (api/supervising-strategy cell child-ref)
        action (if (fn? on-failure)
                 (on-failure child-ref anomaly)
                 on-failure)]
    (logger/log! (api/self cell) (format "Supervising decision for %s: %s" child-ref action))
    (case action
      :globe/resume (api/tell! child-ref (-> (msg/make-signal :globe/resume)
                                             (msg/from (api/self cell))))
      :globe/restart (api/tell! child-ref (-> (msg/make-signal :globe/restart)
                                              (msg/from (api/self cell))))
      :globe/restart-all (tell-children-to-restart cell)
      :globe/escalate (handle-anomaly cell nil anomaly)
      :globe/stop (api/tell! child-ref (msg/make-signal :globe/poison-pill))
      (handle-anomaly
       cell nil
       {::anom/category ::anom/fault
        ::anom/message (str "Invalid supervising action: " action)}))))

(defn- handle-supervised-resume
  [cell]
  (api/switch-to-mode! cell ::running)
  (api/resume! cell))

(defn- handle-supervised-restart
  [cell]
  (api/switch-to-mode! cell ::restarting)
  (tell-children-to-stop cell))

(defn- default-behavior
  [ctx cell actor-fn actor-props msg]
  (match msg
         {::msg/subj :globe/create}
         (api/become! cell (actor-fn ctx actor-props))
         :else 
         (logger/log! (api/self ctx) "Message ignored:" (::msg/subj msg))))

(defrecord Cell [system !self actor-fn actor-props supervisor
                 !children !behavior-fn !mode !life-cycle-hooks]
  api/Children
  (add-child! [_ child-ref on-failure]
    (swap! !children assoc (api/get-name child-ref)
           {:ref child-ref :on-failure on-failure}))
  
  (remove-child! [_ child-ref]
    (logger/log! @!self "Removing child:" (api/get-name child-ref))
    (swap! !children dissoc (api/get-name child-ref))
    (when-not (seq @!children)
      (api/tell! @!self (msg/make-signal ::children-stopped))))

  (get-child-ref [_ child-name]
    (get-in @!children [child-name :ref]))

  api/Supervisor
  (supervising-strategy [_ child-ref]
    (let [child-name (api/get-name child-ref)]
      (get-in @!children [child-name :on-failure])))

  api/Spawner
  (spawn!
    [this actor-id actor-fn actor-props
     {:keys [on-failure]
      :or {on-failure :globe/restart}}]
    (let [child-uri (uris/child-uri (api/uri @!self) actor-id)
          child-ref (api/local-actor-ref system child-uri actor-fn actor-props @!self nil)]
      (api/add-child! this child-ref on-failure)
      (api/start! child-ref)
      child-ref))

  api/MessageHandler
  (handle-message! [this msg]
    (if-let [behavior-fn @!behavior-fn]
      (async/go-loop [result (err-or (behavior-fn msg))]
        (cond
          (chan? result) (recur (<! result))
          (::anom/category result) (handle-anomaly this msg result)
          :else result))
      (println "Message ignored:" (::msg/subj msg))))

  api/HasBehavior
  (become! [_ behavior-fn]
    (reset! !behavior-fn behavior-fn))

  api/PartOfTree
  (self [_] @!self)
  (supervisor [_] supervisor)

  api/UnhandledMessageHandler
  (handle-unhandled-message! [this msg]
    (match [@!mode msg]
           [::running {::msg/subj :globe/poison-pill}]
           (handle-poison-pill this)
           [::running {::msg/subj ::children-stopped}]
           nil
           [::running
            {::msg/subj :globe/failure ::msg/from child-ref ::msg/body anomaly}]
           (supervise-child this child-ref anomaly)
           [_ {::msg/subj :globe/child-terminated ::msg/from child-ref}]
           (api/remove-child! this child-ref)
           [::awaiting-supervisor-decision
            {::msg/subj :globe/resume ::msg/from supervisor}]
           (handle-supervised-resume this)
           [::awaiting-supervisor-decision
            {::msg/subj :globe/restart ::msg/from supervisor}]
           (handle-supervised-restart this)
           [::stopping {::msg/subj ::children-stopped}]
           (api/terminate! this)
           [::restarting {::msg/subj ::children-stopped}]
           (api/restart! this)
           :else 
           (logger/log! @!self "Unhandled message [" @!mode "]:" (::msg/subj msg))))

  api/HasMode
  (switch-to-mode! [this mode]
    (reset! !mode mode))

  api/Terminatable
  (terminate! [this]
    (logger/log! @!self "Terminating...")
    (api/terminate! @!self))

  api/Suspendable
  (suspend! [this]
    (api/suspend! @!self))
  
  (resume! [this]
    (api/resume! @!self))

  api/WithLifeCycleHooks
  (on-cleanup [this f]
    (swap! !life-cycle-hooks update :on-cleanup conj f))

  api/LifeCycle
  (cleanup! [this]
    (doseq [f (:on-cleanup @!life-cycle-hooks)]
      (f))
    (swap! !life-cycle-hooks assoc :on-cleanup [])
    this)
  (restart! [this]
    (api/cleanup! this)
    (api/init! this)
    (api/tell! @!self (msg/make-signal :globe/create))
    (api/resume! this))
  (init! [this]
    (let [ctx (context/make-context this)
          behavior-fn (partial #'default-behavior ctx this actor-fn actor-props)]
      (api/become! this behavior-fn)
      (api/switch-to-mode! this ::running))
    this))

(defn make-cell
  [system actor-fn actor-props supervisor]
  (let [cell (map->Cell
              {:system system
               :!self (atom nil)
               :actor-fn actor-fn
               :actor-props actor-props
               :supervisor supervisor
               :!children (atom {})
               :!behavior-fn (atom nil)
               :!mode (atom ::running)
               :!life-cycle-hooks (atom {:on-cleanup []})})]
    (api/init! cell)))

(defn init!
  [cell self]
  (reset! (:!self cell) self))

