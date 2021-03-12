(ns lekstuga.api.transport)

(defprotocol Transport
  (scheme [this] "Returns the transport scheme"))
