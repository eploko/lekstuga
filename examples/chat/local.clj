(ns examples.chat.local
  (:require
   [globe.core :as globe]
   [examples.chat.server :as chat-server]
   [examples.chat.client :as chat-client]))

(def server-sys (globe/start-system! "server-sys"))
(def server (globe/spawn! server-sys "chat-server" chat-server/server-actor nil nil))

(def server-uri "globe://server-sys/user/chat-server")

(comment
  (globe/tell! server (globe/msg :globe/poison-pill))
  (globe/stop-system! server-sys)
  ,)

(def client-sys (globe/start-system! "client-sys"))

;; Bob

(def bob (globe/spawn! client-sys "bob" chat-client/client-actor server-uri nil))

(globe/tell! bob (globe/msg ::connect "Bob"))
(globe/tell! bob (globe/msg ::say "Hello Alice!"))
(globe/tell! bob (globe/msg ::nick "Mr. Bob"))

;; Alice

(def alice (globe/spawn! client-sys "alice" chat-client/client-actor server-uri nil))

(globe/tell! alice (globe/msg ::connect "Alice"))
(globe/tell! alice (globe/msg ::say "Hello Bob!"))

(comment
  (globe/tell! bob (globe/msg :globe/poison-pill))
  (globe/tell! alice (globe/msg :globe/poison-pill))
  (globe/stop-system! client-sys)
  ,)
