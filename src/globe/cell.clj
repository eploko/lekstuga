(ns globe.cell
  (:require
   [clojure.core.match :refer [match]]
   [globe.api :as api]
   [globe.context :as context]
   [globe.logger :as logger]
   [globe.msg :as msg]
   [globe.uris :as uris]))

(defn- tell-children!
  [cell msg & {:keys [on-no-children]
               :or {on-no-children (fn [])}}]
  (let [child-refs (deref (:!children cell))]
    (if (seq child-refs)
      (doseq [child-ref child-refs]
        (api/tell! child-ref msg))
      (on-no-children))))

(defn- tell-children-to-stop
  [cell]
  (logger/log! (api/self cell) "telling all children to stop...")
  (tell-children! cell (msg/make-signal :globe/poison-pill)
                  :on-no-children
                  #(api/tell! (api/self cell) (msg/make-signal ::children-stopped))))

(defn- handle-poison-pill
  [cell]
  (logger/log! (api/self cell) "Received a poison pill!")
  (api/suspend! cell)
  (api/switch-to-mode! cell ::stopping)
  (tell-children-to-stop cell))

(defrecord Cell [system !self actor-fn actor-props supervisor
                 !children !behavior-fn !mode]
  api/Children
  (add-child! [_ child-ref]
    (swap! !children conj child-ref))
  
  (remove-child! [_ child-ref]
    (logger/log! @!self "removing child:" (api/uri child-ref))
    (swap! !children disj child-ref)
    (when-not (seq @!children)
      (api/tell! @!self (msg/make-signal ::children-stopped))))

  api/Spawner
  (spawn! [this actor-id actor-fn actor-props]
    (let [child-uri (uris/child-uri (api/uri @!self) actor-id)
          child-ref (api/local-actor-ref system child-uri actor-fn actor-props @!self)]
      (api/add-child! this child-ref)
      (api/start! child-ref)
      child-ref))

  api/MessageHandler
  (handle-message! [this msg]
    (if-let [behavior-fn @!behavior-fn]
      (behavior-fn msg)
      (println "Message ignored:" (::msg/subj msg))))

  api/HasBehavior
  (become! [_ behavior-fn]
    (reset! !behavior-fn behavior-fn))

  api/HasSelf
  (self [_]
    @!self)

  api/UnhandledMessageHandler
  (handle-unhandled-message! [this msg]
    (match [@!mode msg]
           [::running {::msg/subj :globe/poison-pill}]
           (handle-poison-pill this)
           [_ {::msg/subj :globe/child-terminated ::msg/from child-ref}]
           (api/remove-child! this child-ref)
           [::stopping {::msg/subj ::children-stopped}]
           (api/terminate! this)
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
    (api/resume! @!self)))

(defn- default-behavior
  [ctx cell actor-fn actor-props msg]
  (match msg
         {::msg/subj :globe/create}
         (api/become! cell (actor-fn ctx actor-props))
         :else 
         (println "Message handled:" (::msg/subj msg))))

(defn make-cell
  [system actor-fn actor-props supervisor]
  (let [cell (map->Cell
              {:system system
               :!self (atom nil)
               :actor-fn actor-fn
               :actor-props actor-props
               :supervisor supervisor
               :!children (atom #{})
               :!behavior-fn (atom nil)
               :!mode (atom ::running)})
        ctx (context/make-context cell)
        behavior-fn (partial #'default-behavior ctx cell actor-fn actor-props)]
    
    (api/become! cell behavior-fn)
    cell))

(defn init!
  [cell self]
  (reset! (:!self cell) self))

