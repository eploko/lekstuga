(ns user
  (:require
   [clojure.core.match :refer [match]]
   [globe.core]))

(comment
  (def msg
    [true "someone" :stop])
  (match [msg]
         [[false _ _]] :r1
         [[true a :stop]] a
         :else :no-match)
  ,)
