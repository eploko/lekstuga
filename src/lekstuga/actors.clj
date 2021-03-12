(ns lekstuga.actors
  (:require
   [clojure.core.match :refer [match]]
   [lekstuga.api :as api]
   [lekstuga.msg :as msg]
   [lekstuga.logger :as logger]))

(defn temp-guardian
  [ctx _props]
  (logger/debug (api/self ctx) "Initialising...")
  (partial api/handle-message! ctx))

(defn user-guardian
  [ctx _props]
  (logger/debug (api/self ctx) "Initialising...")

  (let [!children-count (atom 0)]

    (fn [msg]
      (match msg
             {::msg/subj :lekstuga/new-child}
             (swap! !children-count inc)
             {::msg/subj :lekstuga/terminated}
             (when (zero? (swap! !children-count dec))
               (api/tell! (api/self ctx) (msg/make-signal :lekstuga/poison-pill)))
             :else 
             (api/handle-message! ctx msg)))))

(defn system-guardian
  [ctx _props]
  (logger/debug (api/self ctx) "Initialising...")
  (api/spawn! ctx "temp" temp-guardian nil nil)
  (partial api/handle-message! ctx))

(defn root-guardian
  [ctx _props]
  (logger/debug (api/self ctx) "Initialising...")
  (let [user-guardian
        (api/spawn! ctx "user" user-guardian nil nil)]
    (api/spawn! ctx "system" system-guardian nil nil)
    (api/link! user-guardian (api/self ctx))

    (api/on-cleanup ctx (fn []
                          (logger/debug (api/self ctx) "Cleaning up...")
                          (api/unlink! user-guardian (api/self ctx))))
    
    (fn [msg]
      (match msg
             {::msg/subj :lekstuga/terminated ::msg/from user-guardian}
             (do
               (logger/info (api/self ctx) "The user guardian has terminated. Shutting down...")
               (api/tell! (api/self ctx) (msg/make-signal :lekstuga/poison-pill)))
             :else 
             (api/handle-message! ctx msg)))))

