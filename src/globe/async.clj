(ns globe.async
  "`core.async` additions."
  (:require [clojure.core.async :as async :refer [go go-loop]]
            [clojure.core.async.impl.protocols :as async-protocols]
            [cognitect.anomalies :as anom]))

(defn chan?
  "Returns `true` if `x` is a `core.async` channel."
  [x]
  (satisfies? clojure.core.async.impl.protocols/ReadPort x))

(defn throw-err
  "Throw if is error, will be different in ClojureScript"
  [v] 
  (if (isa? java.lang.Throwable v) (throw v) v))

(defmacro <?
  "Version of <! that throw Exceptions that come out of a channel."
  [c]
  `(throw-err (async/<! ~c)))

(defmacro err-or
  "If body throws an exception, catch it and return an anomaly." 
  [& body]
  `(try 
     ~@body
     (catch Throwable e#
       {::anom/category ::anom/fault
        ::anom/message (.getMessage e#)})))

(defmacro go-safe [& body]
  `(go (err-or ~@body)))

(defn <take-all
  [& chs]
  (go-loop [result []
            chs chs]
    (if (seq? chs)
      (let [[v port] (async/alts! chs)]
        (if (nil? v)
          (recur result (seq (remove #{port} chs)))
          (recur (conj result v) chs)))
      result)))

(deftype UnboundBuffer [!buf]
  async-protocols/Buffer
  (full? [this]
    false)
  (remove! [this]
    (ffirst (swap-vals! !buf pop)))
  (add!* [this itm]
    (swap! !buf conj itm))
  (close-buf! [this] (reset! !buf []))
  clojure.lang.Counted
  (count [this]
    (count @!buf))
  clojure.lang.IDeref
  (deref [this]
    @!buf))

(defn unbound-buf
  "Creates and returns an unbound buffer.
  Use at your own risk."
  []
  (UnboundBuffer. (atom [])))
