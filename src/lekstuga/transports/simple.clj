(ns lekstuga.transports.simple
  (:require
   [cognitect.anomalies :as anom]
   [lekstuga.async :refer [go-safe]]
   [lekstuga.api :as api]
   [lekstuga.api.transport :as transport-api]
   [lekstuga.uris :as uris]))

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

