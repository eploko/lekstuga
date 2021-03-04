(ns globe.context
  (:require
   [globe.api :as api]))

(defrecord Context [cell]
  api/Spawner
  (spawn! [this actor-id actor-fn actor-props opts]
    (api/spawn! cell actor-id actor-fn actor-props opts))

  api/PartOfTree
  (self [_] (api/self cell))
  (supervisor [_] (api/supervisor cell))

  api/HasBehavior
  (become! [_ behavior-fn]
    (api/become! cell behavior-fn))

  api/MessageHandler
  (handle-message! [_ msg]
    (api/handle-unhandled-message! cell msg))

  api/WithLifeCycleHooks
  (on-cleanup [_ f]
    (api/on-cleanup cell f))

  api/RefResolver
  (<resolve-ref! [_ str-or-uri]
    (api/<resolve-ref! cell str-or-uri)))

(defn make-context
  [cell]
  (map->Context
   {:cell cell}))
