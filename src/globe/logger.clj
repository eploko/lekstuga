(ns globe.logger
  (:require
   [clojure.tools.logging :as log]
   [globe.api :as api]))

(defn actor-ref-message
  [actor-ref]
  (format "%s:" (api/uri actor-ref)))

(defmacro debug
  [actor-ref & more]
  `(log/debug (actor-ref-message ~actor-ref) ~@more))

(defmacro error
  [actor-ref & more]
  `(log/error (actor-ref-message ~actor-ref) ~@more))

(defmacro fatal
  [actor-ref & more]
  `(log/fatal (actor-ref-message ~actor-ref) ~@more))

(defmacro info
  [actor-ref & more]
  `(log/info (actor-ref-message ~actor-ref) ~@more))

(defmacro trace
  [actor-ref & more]
  `(log/trace (actor-ref-message ~actor-ref) ~@more))

(defmacro warn
  [actor-ref & more]
  `(log/warn (actor-ref-message ~actor-ref) ~@more))

(defn log
  ([actor-ref level message]
   (log/log level (str (actor-ref-message actor-ref) " " message)))
  ([actor-ref level throwable message]
   (log/log level throwable (str (actor-ref-message actor-ref) " " message))))
