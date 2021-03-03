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
  (api/spawn! ctx "temp" temp-guardian nil)
  (partial api/handle-message! ctx))

(defn root-guardian
  [ctx _props]
  (logger/log! (api/self ctx) "Initialising...")
  (let [user-guardian
        (api/spawn! ctx "user" user-guardian nil)]
    (api/spawn! ctx "system" system-guardian nil)
    (api/link! user-guardian (api/self ctx))

    ;; TODO: Implement cleanups
    #_(api/on-cleanup ctx #(api/unlink! ctx user-guardian))
    
    (fn [msg]
      (match msg
             {::msg/subj :globe/terminated ::msg/from user-guardian}
             (do
               (logger/log! (api/self ctx) "The user guardian has terminated. Shutting down...")
               (api/tell! (api/self ctx) (msg/make-signal :globe/poison-pill)))
             :else 
             (api/handle-message! ctx msg)))))

