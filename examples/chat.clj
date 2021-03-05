(ns examples.chat
  (:require
   [clojure.core.match :refer [match]]
   [cognitect.anomalies :as anom]
   [globe.async :refer [go-safe <?]]
   [globe.core :as globe]
   [globe.logger :refer [debug info error]]
   [globe.msg :as msg]
   [globe.api :as api]))

;; Server

(defn- tell-all!
  [actor-refs msg]
  (doseq [actor-ref actor-refs]
    (globe/tell! actor-ref msg)))

(defn server-actor
  [ctx _props]
  (let [!clients (atom {})]

    (globe/on-cleanup
     ctx (fn []
           (doseq [client (keys @!clients)]
             (globe/unlink! client (globe/self ctx)))))

    (fn [msg]
      (match msg
             {::msg/subj ::connect ::msg/from client ::msg/body nick}
             (do
               (swap! !clients assoc client nick)
               (globe/link! client (globe/self ctx))
               (globe/tell! client (-> (globe/msg ::connected)
                                       (globe/from (globe/self ctx))))
               (tell-all! (remove #{client} (keys @!clients))
                          (globe/msg ::user-connected nick)))
             {::msg/subj :globe/terminated ::msg/from client}
             (let [nick (@!clients client)]
               (swap! !clients dissoc client)
               (tell-all! (keys @!clients)
                          (globe/msg ::user-left nick)))
             {::msg/subj ::say ::msg/from client ::msg/body s}
             (tell-all! (keys @!clients)
                        (globe/msg ::user-says
                                   {:nick (@!clients client)
                                    :text s}))
             {::msg/subj ::nick ::msg/from client ::msg/body new-nick}
             (let [old-nick (@!clients client)]
               (swap! !clients assoc client new-nick)
               (tell-all! (remove #{client} (keys @!clients))
                          (globe/msg ::nick-changed {:old old-nick :new  new-nick})))
             :else 
             (globe/handle-message! ctx msg)))))

(def server-sys (globe/start-system! "server-sys"))
(def server (globe/spawn! server-sys "chat-server" server-actor nil nil))
(def server-uri "globe://server-sys/user/chat-server")

(comment
  (globe/tell! server (globe/msg :globe/poison-pill))
  (globe/stop-system! server-sys)
  ,)

;; Client

(defn- display
  [nick s & more]
  (println
   (format "%s's side | %s"
           nick (apply format s more))))

(defn client-actor
  [ctx server-uri]
  (letfn
      [(connecting-behavior
         [nick msg]
         (match msg
                {::msg/subj ::connected ::msg/from server-ref}
                (do
                  (globe/link! server-ref (globe/self ctx))
                  (globe/become! ctx (partial connected-behavior server-ref nick)))
                :else 
                (globe/handle-message! ctx msg)))

       (connected-behavior
         [server-ref nick msg]
         (match msg
                {::msg/subj :globe/terminated ::msg/from server-ref}
                (do
                  (display nick "Server is down. Please connect again.")
                  (globe/become! ctx default-behavior))
                {::msg/subj ::say ::msg/body s}
                (globe/tell! server-ref (-> (globe/msg ::say s)
                                            (globe/from (globe/self ctx))))
                {::msg/subj ::nick ::msg/body new-nick}
                (do 
                  (globe/tell! server-ref (-> (globe/msg ::nick new-nick)
                                              (globe/from (globe/self ctx))))
                  (globe/become! ctx (partial connected-behavior server-ref new-nick)))
                {::msg/subj ::user-connected ::msg/body other-nick}
                (display nick "%s enters the room..." other-nick)
                {::msg/subj ::user-says ::msg/body {:nick other-nick :text s}}
                (display nick "%s: %s" other-nick s)
                {::msg/subj ::nick-changed ::msg/body {:old old :new new}}
                (display nick "%s is now known as %s." old new)
                {::msg/subj ::user-left ::msg/body other-nick}
                (display nick "%s left the room..." other-nick)
                :else
                (globe/handle-message! ctx msg)))
       
       (default-behavior
         [msg]
         (match msg
                {::msg/subj ::connect ::msg/body nick}
                (go-safe
                 (info (api/self ctx) "Resolving ref for:" server-uri)
                 (let [server-ref (<? (globe/<resolve-ref! ctx server-uri))]
                   (if (::anom/category server-ref)
                     (do
                       (info (api/self ctx) "Got anomaly:" server-ref)
                       (error (api/self ctx) "Server unavailable:" server-uri))
                     (do
                       (globe/tell! server-ref (-> (globe/msg ::connect nick)
                                                   (globe/from (globe/self ctx))))
                       (globe/become! ctx (partial connecting-behavior nick))))))
                {::msg/subj ::connected ::msg/from server-ref}
                (globe/become! ctx (partial connected-behavior server-ref))
                :else 
                (globe/handle-message! ctx msg)))]
    
      default-behavior))

(def client-sys (globe/start-system! "client-sys"))

;; Bob

(def bob (globe/spawn! client-sys "bob" client-actor server-uri nil))

(globe/tell! bob (globe/msg ::connect "Bob"))
(globe/tell! bob (globe/msg ::say "Hello Alice!"))
(globe/tell! bob (globe/msg ::nick "Mr. Bob"))

;; Alice

(def alice (globe/spawn! client-sys "alice" client-actor server-uri nil))

(globe/tell! alice (globe/msg ::connect "Alice"))
(globe/tell! alice (globe/msg ::say "Hello Bob!"))

(comment
  (globe/tell! bob (globe/msg :globe/poison-pill))
  (globe/tell! alice (globe/msg :globe/poison-pill))
  (globe/stop-system! client-sys)
  ,)
