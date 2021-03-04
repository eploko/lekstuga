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

(defn server-actor
  [ctx _props]
  (let [!clients (atom #{})]

    (globe/on-cleanup
     ctx (fn []
           (doseq [client @!clients]
             (globe/unlink! client (globe/self ctx)))))

    (fn [msg]
      (match msg
             {::msg/subj ::connect ::msg/from client}
             (do
               (swap! !clients conj client)
               (globe/link! client (globe/self ctx))
               (globe/tell! client (globe/msg ::connected)))
             :else 
             (globe/handle-message! ctx msg)))))

(def server-sys (globe/start-system! "server-sys"))
(def server (globe/spawn! server-sys "chat-server" server-actor nil nil))

(comment
  (globe/tell! server (globe/msg :globe/poison-pill))
  ,)

;; Client

(defn client-actor
  [ctx {:keys [server-uri nick]}]
  (letfn
      [(connected-behavior
         [msg]
         (globe/handle-message! ctx msg))
       
       (default-behavior
         [msg]
         (match msg
                {::msg/subj ::connect}
                (go-safe
                 (info (api/self ctx) "Resolving ref for:" server-uri)
                 (let [server-ref (<? (globe/<resolve-ref! ctx server-uri))]
                   (if (::anom/category server-ref)
                     (do
                       (info (api/self ctx) "Got anomaly:" server-ref)
                       (error (api/self ctx) "Server unavailable:" server-uri))
                     (globe/tell! server-ref (-> (globe/msg ::connect)
                                                 (globe/from (globe/self ctx)))))))
                {::msg/subj ::connected}
                (globe/become! ctx connected-behavior)
                :else 
                (globe/handle-message! ctx msg)))]
    
      default-behavior))

(def client-sys (globe/start-system! "client-sys"))
(def bob (globe/spawn! client-sys "bob" client-actor
                       {:server-uri "globe://server-sys/user/chat-server"
                        :nick "Bob"}
                       nil))

(globe/tell! bob (globe/msg ::connect))

(comment
  (globe/tell! bob (globe/msg :globe/poison-pill))
  (globe/stop-system! client-sys)
  ,)
