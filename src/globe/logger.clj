(ns globe.logger
  (:require
   [clojure.string :as str]
   [globe.api :as api]))

(defn log!
  [actor-ref & args]
  (->> args
       (str/join " ")
       (format "%s: %s" (api/uri actor-ref))
       println))
