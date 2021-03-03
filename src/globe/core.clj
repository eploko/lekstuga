(ns globe.core
  (:require [globe.actor-system :as system]
            [globe.api :as api]
            [globe.ask :as ask]
            [globe.msg :as msg]))

(def start-system! #'system/start!)
(def spawn! #'api/spawn!)
(def tell! #'api/tell!)
(def <ask! #'ask/<ask!)
(def reply! #'ask/reply!)
(def msg #'msg/make-msg)
(def self #'api/self)
(def handle-message! #'api/handle-message!)


