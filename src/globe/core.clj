(ns globe.core
  (:require [globe.actor-system :as system]
            [globe.api :as api]
            [globe.logger :as logger]
            [globe.msg :as msg]))

(def log! #'logger/log!)
(def start-system! #'system/start!)
(def spawn! #'api/spawn!)
(def tell! #'api/tell!)
(def msg #'msg/make-msg)


