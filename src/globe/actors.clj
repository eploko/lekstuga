(ns globe.actors
  (:require
   [clojure.core.match :refer [match]]
   [globe.api :as api]
   [globe.msg :as msg]
   [globe.logger :as logger]))

(defn temp-guardian
  [ctx _props]
  (logger/log! (api/self ctx) "Initialising...")
  (partial api/handle-message! ctx))

(defn user-guardian
  [ctx _props]
  (logger/log! (api/self ctx) "Initialising...")

  (let [!children-count (atom 0)]

    (fn [msg]
      (match msg
             {::msg/subj :globe/new-child}
             (swap! !children-count inc)
             {::msg/subj :globe/terminated}
             (when (zero? (swap! !children-count dec))
               (api/tell! (api/self ctx) (msg/make-signal :globe/poison-pill)))
             :else 
             (api/handle-message! ctx msg)))))

(defn system-guardian
  [ctx _props]
  (logger/log! (api/self ctx) "Initialising...")

  (fn [msg]
    (match msg
           :else 
           (api/handle-message! ctx msg))))

(defn root-guardian
  [ctx _props]
  (logger/log! (api/self ctx) "Initialising...")
  
  (fn [msg]
    (match msg
           :else 
           (api/handle-message! ctx msg))))

