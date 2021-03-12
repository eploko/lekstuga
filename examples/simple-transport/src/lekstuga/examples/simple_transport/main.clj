(ns lekstuga.examples.simple-transport.main
  (:require
   [clojure.core.async :as async :refer [<! go]]
   [clojure.core.match :refer [match]]
   [lekstuga.core :as lekstuga]
   [lekstuga.msg :as msg]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.tools.logging :refer [use-timbre]]))

(use-timbre)

(let [level->str
      {:trace "T" :debug "D" :info   "I" :warn "W"
       :error "E" :fatal "F" :report "R"}]
  (timbre/merge-config! 
   {:min-level :debug
    :timestamp-opts 
    {:pattern ""}
    :output-fn
    (fn [{:keys [level vargs_]}]
      (let [event (apply str (force vargs_))]
        (str (get level->str level level) " " event)))}))

(comment
 (defn ping
   [ctx _props]
   (fn [msg]
     (match msg
            {::msg/subj :ping}
            (lekstuga/reply! msg :pong)
            :else
            (lekstuga/handle-message! ctx msg))))

 (def s1 (lekstuga/start-system! "s1"))
 (def ping-actor (lekstuga/spawn! s1 "ping" ping nil nil))

 (def s2 (lekstuga/start-system! "s2"))

 (go
  (println "Resolve result:"
           (<! (lekstuga/<resolve-ref! s2 "lekstuga.simple://s1/user/ping"))))

 (go
  (println "reply:"
             (<! (lekstuga/<ask! ping-actor (lekstuga/msg :ping)))))

 (lekstuga/tell! ping-actor (lekstuga/msg :lekstuga/poison-pill))
 (lekstuga/stop-system! s1)

 ,)
