(ns globe.cell
  (:require
   [globe.api :as api]
   [globe.uris :as uris]))

(defrecord Cell [system !self actor-fn actor-props supervisor !children]
  api/Children
  (add-child! [_ child-ref]
    (swap! !children conj child-ref))

  api/Spawner
  (spawn! [this actor-id actor-fn actor-props]
    (let [child-uri (uris/child-uri (api/uri @!self) actor-id)
          child-ref (api/local-actor-ref system child-uri actor-fn actor-props @!self)]
      (api/add-child! this child-ref)
      (api/start! child-ref)
      child-ref))

  api/MessageHandler
  (handle-message! [this msg]
    (println "Handling message...")))

(defn make-cell
  [system actor-fn actor-props supervisor]
  (map->Cell
   {:system system
    :!self (atom nil)
    :actor-fn actor-fn
    :actor-props actor-props
    :supervisor supervisor
    :!children (atom #{})}))

(defn init!
  [cell self]
  (reset! (:!self cell) self))

