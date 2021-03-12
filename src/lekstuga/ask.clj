(ns lekstuga.ask
  (:require
   [clj-uuid :as uuid]
   [clojure.core.match :refer [match]]
   [lekstuga.api :as api]
   [lekstuga.logger :as logger]
   [lekstuga.msg :as msg]
   [clojure.core.async :as async :refer [>! chan go]]))

(defn ask-actor
  [ctx {:keys [target msg reply-ch]}]
  (logger/debug (api/self ctx) "Initialising...")
  (api/tell! target (msg/from msg (api/self ctx)))

  (fn [msg]
    (match msg
           {::msg/subj ::reply ::msg/body body}
           (do
             (go (>! reply-ch body)
                 (async/close! reply-ch))
             (api/tell! (api/self ctx) (msg/make-msg :lekstuga/poison-pill)))
           :else (api/handle-message! ctx msg))))

(defn <ask!
  ([actor-ref msg]
   (<ask! actor-ref 5000 msg))
  ([actor-ref timeout-ms msg]
   (let [temp-cell (-> actor-ref api/system api/registry api/temp-guardian api/underlying)
         ch (chan)
         timeout-ch (async/timeout timeout-ms)
         ask-actor-ref (api/spawn! temp-cell (uuid/v4) ask-actor
                                   {:target actor-ref :msg msg :reply-ch ch}
                                   nil)]
     (go
       (let [[v p] (async/alts! [ch timeout-ch])]
         (if (= p timeout-ch)
           (do
             (logger/error actor-ref "<ask! timed out:" (::msg/subj msg))
             (async/close! ch)
             (api/tell! ask-actor-ref (msg/make-msg :lekstuga/poison-pill))
             :timeout)
           v))))))

(defn reply!
  [msg reply-body]
  (api/tell! (::msg/from msg) (msg/make-msg ::reply reply-body)))
