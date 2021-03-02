(ns globe.dispatcher
  (:require
   [globe.api :as api]
   [clojure.core.async :as async :refer [go-loop]]))

(defrecord Dispatcher [!signal-ch]
  api/Dispatcher
  (start-dispatching! [this mailbox cell]
    (let [signal-ch (reset! !signal-ch (async/chan))]
      (go-loop []
        (let [ports (cons signal-ch @mailbox)
              [msg port] (async/alts! ports :priority true)]
          (when-not (identical? port signal-ch)
            (api/handle-message! cell msg)
            (recur))))))
  
  (stop-dispatching! [this]
    (swap! !signal-ch
           (fn [x]
             (when x
               (async/close! x))
             nil))))

(defn make-dispatcher
  []
  (map->Dispatcher
   {:!signal-ch (atom nil)}))

