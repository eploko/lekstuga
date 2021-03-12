(ns lekstuga.examples.chat.main
  (:require
   [lekstuga.core :as lekstuga]
   [lekstuga.examples.chat.server :as chat-server]
   [lekstuga.examples.chat.client :as chat-client]
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
  (let [server-sys (lekstuga/start-system! "server-sys")
        server (lekstuga/spawn! server-sys "chat-server" chat-server/server-actor nil nil)
        server-uri "lekstuga://server-sys/user/chat-server"
        client-sys (lekstuga/start-system! "client-sys")
        !bob (atom (lekstuga/spawn! client-sys "bob" chat-client/client-actor server-uri nil))
        !alice (atom (lekstuga/spawn! client-sys "alice" chat-client/client-actor server-uri nil))
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
            (lekstuga/tell! @!who (lekstuga/msg :chat/connect (role !who)))
            (recur !who))
          "/disconnect"
          (do
            (lekstuga/tell! @!who (lekstuga/msg :lekstuga/poison-pill))
            (reset! !who
                    (lekstuga/spawn! client-sys
                                  (str/lower-case (role !who))
                                  chat-client/client-actor server-uri nil))
            (recur !who))
          "/nick"
          (do
            (lekstuga/tell! @!who (lekstuga/msg :chat/nick opt))
            (recur !who))
          "/exit"
          (println "Bye!")
          (do
            (lekstuga/tell! @!who (lekstuga/msg :chat/say s))
            (recur !who)))))
    
    (lekstuga/tell! @!bob (lekstuga/msg :lekstuga/poison-pill))
    (lekstuga/tell! @!alice (lekstuga/msg :lekstuga/poison-pill))
    (lekstuga/stop-system! client-sys)
    (lekstuga/tell! server (lekstuga/msg :lekstuga/poison-pill))
    (lekstuga/stop-system! server-sys)))
