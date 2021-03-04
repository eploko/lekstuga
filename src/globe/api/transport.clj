(ns globe.api.transport)

(defprotocol Transport
  (get-protocol-name [this] "Returns the protocol name"))
