(ns globe.transports.local
  (:require
   [globe.api.transport :as transport-api]))

(defrecord LocalTransport [protocol-name]
  transport-api/Transport
  (get-protocol-name [_] protocol-name))

(defn- local-transport
  [protocol-name]
  (map->LocalTransport
   {:protocol-name protocol-name}))

(def shared-instance
  (local-transport "globe"))
