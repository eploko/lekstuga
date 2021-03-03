(ns globe.refs.bubble
  (:require
   [globe.api :as api]))

(defrecord BubbleRef []
  api/MessageTarget
  (tell! [_ _msg])

  clojure.lang.IFn
  (toString [_]
    (str "<#BubbleRef>")))

(defmethod print-method BubbleRef
  [o w]
  (print-simple (.toString o) w))

(defn bubble-ref
  []
  (BubbleRef.))
