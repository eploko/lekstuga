(ns examples.ex02
  (:require
   [clojure.core.async :as async :refer [<! go]]
   [clojure.core.match :refer [match]]
   [globe.core :as globe]
   [globe.msg :as msg]
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

(defn ping
  [ctx _props]
  (fn [msg]
    (match msg
           {::msg/subj :ping}
           (globe/reply! msg :pong)
           :else
           (globe/handle-message! ctx msg))))

(def s1 (globe/start-system! "s1"))
(def ping-actor (globe/spawn! s1 "ping" ping nil nil))

(def s2 (globe/start-system! "s2"))

(go
  (println "Resolve result:"
           (<! (globe/<resolve-ref! s2 "globe.simple://s1/user/ping"))))

(go
  (println "reply:"
             (<! (globe/<ask! ping-actor (globe/msg :ping)))))

(globe/tell! ping-actor (globe/msg :globe/poison-pill))
(globe/stop-system! s1)

(comment
  ,)
