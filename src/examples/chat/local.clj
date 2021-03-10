(ns examples.chat.local
  (:require
   [globe.core :as globe]
   [examples.chat.server :as chat-server]
   [examples.chat.client :as chat-client]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.tools.logging :refer [use-timbre]]))

(use-timbre)

(let [level->str
      {:trace "T" :debug "D" :info  "I" :warn  "W"
       :error "E" :fatal "F" :report "R"}]
  (timbre/merge-config! 
   {:min-level :info
    :timestamp-opts 
    {:pattern ""}
    :output-fn
    (fn [{:keys [level vargs_]}]
      (let [event (apply str (force vargs_))]
        (str (get level->str level level) " " event)))}))

(defn run
  [_opts]
  (let [server-sys (globe/start-system! "server-sys")
        server (globe/spawn! server-sys "chat-server" chat-server/server-actor nil nil)
        server-uri "globe://server-sys/user/chat-server"
        client-sys (globe/start-system! "client-sys")
        !bob (atom (globe/spawn! client-sys "bob" chat-client/client-actor server-uri nil))
        !alice (atom (globe/spawn! client-sys "alice" chat-client/client-actor server-uri nil))
        role (fn [!who] (if (= !who !bob) "Bob" "Alice"))]
    (println "Awesome Chat v1.0")
    (println)
    (println "You are now chatting on behalf of Bob.")
    (println)
    (println "Available commands:")
    (println "  /bob              to become Bob")
    (println "  /alice            to become Alice")
    (println "  /connect          to connect to the server")
    (println "  /disconnect       to disconnect from the server")
    (println "  /nick new-nick    to change your nick")
    (println "  /exit             to exit")
    (println)
    (println "Or just type some text to chat...")
    (println)

    (loop [!who !bob]
      (let [s (str/trim (read-line))
            [cmd opt] (str/split s #"\s" 2)]
        (case cmd
          ""
          (recur !who)
          "/bob"
          (do
            (println "You are now chatting on behalf of Bob.")
            (recur !bob))
          "/alice"
          (do
            (println "You are now chatting on behalf of Alice.")
            (recur !alice))
          "/connect"
          (do
            (globe/tell! @!who (globe/msg :chat/connect (role !who)))
            (recur !who))
          "/disconnect"
          (do
            (globe/tell! @!who (globe/msg :globe/poison-pill))
            (reset! !who
                    (globe/spawn! client-sys
                                  (str/lower-case (role !who))
                                  chat-client/client-actor server-uri nil))
            (recur !who))
          "/nick"
          (do
            (globe/tell! @!who (globe/msg :chat/nick opt))
            (recur !who))
          "/exit"
          (println "Bye!")
          (do
            (globe/tell! @!who (globe/msg :chat/say s))
            (recur !who)))))
    
    (globe/tell! @!bob (globe/msg :globe/poison-pill))
    (globe/tell! @!alice (globe/msg :globe/poison-pill))
    (globe/stop-system! client-sys)
    (globe/tell! server (globe/msg :globe/poison-pill))
    (globe/stop-system! server-sys)))
