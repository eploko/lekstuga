(ns lekstuga.dispatcher
  (:require
   [lekstuga.api :as api]
   [clojure.core.async :as async :refer [<! go-loop]]))

(defrecord Dispatcher [!signal-ch]
  api/Dispatcher
  (start-dispatching! [this mailbox cell]
    (let [signal-ch (reset! !signal-ch (async/chan))]
      (go-loop []
        (let [ports (cons signal-ch @mailbox)
              [msg _] (async/alts! ports :priority true)]
          (when msg
            (<! (api/handle-message! cell msg))
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

