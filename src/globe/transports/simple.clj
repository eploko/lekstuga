(ns globe.transports.simple
  (:require
   [cognitect.anomalies :as anom]
   [globe.async :refer [go-safe]]
   [globe.api :as api]
   [globe.api.transport :as transport-api]
   [globe.uris :as uris]))

(defrecord SimpleTransport [scheme system]
  transport-api/Transport
  (scheme [_] scheme)

  api/RefResolver
  (<resolve-ref! [this str-or-uri]
    {::anom/category ::anom/not-found
     ::anom/message (str "Ref not found: " str-or-uri)
     :data str-or-uri})

  api/Startable
  (start! [_])
  (stop! [_]))

(defn simple-transport
  [scheme system]
  (map->SimpleTransport
   {:scheme scheme
    :system system}))

