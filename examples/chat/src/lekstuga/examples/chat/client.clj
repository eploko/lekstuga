(ns lekstuga.examples.chat.client
  (:require
   [clojure.core.match :refer [match]]
   [cognitect.anomalies :as anom]
   [lekstuga.async :refer [go-safe <?]]
   [lekstuga.core :as lekstuga]
   [lekstuga.logger :refer [info error]]
   [lekstuga.msg :as msg]))

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
                  (lekstuga/link! server-ref (lekstuga/self ctx))
                  (lekstuga/become! ctx (partial connected-behavior server-ref nick)))
                :else 
                (lekstuga/handle-message! ctx msg)))

       (connected-behavior
         [server-ref nick msg]
         (match msg
                {::msg/subj :lekstuga/terminated ::msg/from server-ref}
                (do
                  (display nick "Server is down. Please connect again.")
                  (lekstuga/become! ctx default-behavior))
                {::msg/subj :chat/say ::msg/body s}
                (lekstuga/tell! server-ref (-> (lekstuga/msg :chat/say s)
                                            (lekstuga/from (lekstuga/self ctx))))
                {::msg/subj :chat/nick ::msg/body new-nick}
                (do 
                  (lekstuga/tell! server-ref (-> (lekstuga/msg :chat/nick new-nick)
                                              (lekstuga/from (lekstuga/self ctx))))
                  (lekstuga/become! ctx (partial connected-behavior server-ref new-nick)))
                {::msg/subj :chat/user-connected ::msg/body other-nick}
                (display nick "%s enters the room..." other-nick)
                {::msg/subj :chat/user-says ::msg/body {:nick other-nick :text s}}
                (display nick "%s: %s" other-nick s)
                {::msg/subj :chat/nick-changed ::msg/body {:old old :new new}}
                (display nick "%s is now known as %s." old new)
                {::msg/subj :chat/user-left ::msg/body other-nick}
                (display nick "%s left the room..." other-nick)
                :else
                (lekstuga/handle-message! ctx msg)))
       
       (default-behavior
         [msg]
         (match msg
                {::msg/subj :chat/connect ::msg/body nick}
                (go-safe
                 (info (lekstuga/self ctx) "Resolving ref for:" server-uri)
                 (let [server-ref (<? (lekstuga/<resolve-ref! ctx server-uri))]
                   (if (::anom/category server-ref)
                     (do
                       (info (lekstuga/self ctx) "Got anomaly:" server-ref)
                       (error (lekstuga/self ctx) "Server unavailable:" server-uri))
                     (do
                       (lekstuga/tell! server-ref (-> (lekstuga/msg :chat/connect nick)
                                                   (lekstuga/from (lekstuga/self ctx))))
                       (lekstuga/become! ctx (partial connecting-behavior nick))))))
                :else 
                (lekstuga/handle-message! ctx msg)))]
    
      default-behavior))
