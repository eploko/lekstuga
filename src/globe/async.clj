(ns globe.async
  "`core.async` additions."
  (:require [clojure.core.async :as async :refer [go-loop]]
            [clojure.core.async.impl.protocols :as async-protocols]))

(defn chan?
  "Returns `true` if `x` is a `core.async` channel."
  [x]
  (satisfies? clojure.core.async.impl.protocols/ReadPort x))

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
