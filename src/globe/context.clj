(ns globe.context
  (:require
   [globe.api :as api]))

(defrecord Context [cell]
  api/Spawner
  (spawn! [this actor-id actor-fn actor-props]
    (api/spawn! cell actor-id actor-fn actor-props))

  api/HasSelf
  (self [_]
    (api/self cell))

  api/HasBehavior
  (become! [_ behavior-fn]
    (api/become! cell behavior-fn))

  api/MessageHandler
  (handle-message! [_ msg]
    (api/handle-unhandled-message! cell msg)))

(defn make-context
  [cell]
  (map->Context
   {:cell cell}))
