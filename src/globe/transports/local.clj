(ns globe.transports.local
  (:require
   [cognitect.anomalies :as anom]
   [globe.async :refer [go-safe]]
   [globe.api :as api]
   [globe.api.transport :as transport-api]
   [globe.uris :as uris]))

(defonce !systems (atom {}))

(defrecord LocalTransport [scheme system]
  transport-api/Transport
  (scheme [_] scheme)

  api/RefResolver
  (<resolve-ref! [this str-or-uri]
    (go-safe 
     (let [system-name (uris/host str-or-uri)]
       (if-let [system (@!systems system-name)]
         (-> system api/registry (api/resolve-child-ref str-or-uri))
         {::anom/category ::anom/not-found
          ::anom/message (str "Ref not found: " str-or-uri)
          :data str-or-uri}))))

  api/Startable
  (start! [_]
    (swap! !systems assoc (api/get-name system) system))
  (stop! [_]
    (swap! !systems dissoc (api/get-name system))))

(defn local-transport
  [scheme system]
  (map->LocalTransport
   {:scheme scheme
    :system system}))

