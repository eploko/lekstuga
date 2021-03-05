(ns chat.client
  (:require
   [clojure.core.match :refer [match]]
   [cognitect.anomalies :as anom]
   [globe.async :refer [go-safe <?]]
   [globe.core :as globe]
   [globe.logger :refer [info error]]
   [globe.msg :as msg]))

(defn- display
  [nick s & more]
  (println
   (format "%s's side | %s"
           nick (apply format s more)))
  (flush))

(defn client-actor
  [ctx server-uri]
  (letfn
      [(connecting-behavior
         [nick msg]
         (match msg
                {::msg/subj :chat/connected ::msg/from server-ref}
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
                {::msg/subj :chat/say ::msg/body s}
                (globe/tell! server-ref (-> (globe/msg :chat/say s)
                                            (globe/from (globe/self ctx))))
                {::msg/subj :chat/nick ::msg/body new-nick}
                (do 
                  (globe/tell! server-ref (-> (globe/msg :chat/nick new-nick)
                                              (globe/from (globe/self ctx))))
                  (globe/become! ctx (partial connected-behavior server-ref new-nick)))
                {::msg/subj :chat/user-connected ::msg/body other-nick}
                (display nick "%s enters the room..." other-nick)
                {::msg/subj :chat/user-says ::msg/body {:nick other-nick :text s}}
                (display nick "%s: %s" other-nick s)
                {::msg/subj :chat/nick-changed ::msg/body {:old old :new new}}
                (display nick "%s is now known as %s." old new)
                {::msg/subj :chat/user-left ::msg/body other-nick}
                (display nick "%s left the room..." other-nick)
                :else
                (globe/handle-message! ctx msg)))
       
       (default-behavior
         [msg]
         (match msg
                {::msg/subj :chat/connect ::msg/body nick}
                (go-safe
                 (info (globe/self ctx) "Resolving ref for:" server-uri)
                 (let [server-ref (<? (globe/<resolve-ref! ctx server-uri))]
                   (if (::anom/category server-ref)
                     (do
                       (info (globe/self ctx) "Got anomaly:" server-ref)
                       (error (globe/self ctx) "Server unavailable:" server-uri))
                     (do
                       (globe/tell! server-ref (-> (globe/msg :chat/connect nick)
                                                   (globe/from (globe/self ctx))))
                       (globe/become! ctx (partial connecting-behavior nick))))))
                :else 
                (globe/handle-message! ctx msg)))]
    
      default-behavior))