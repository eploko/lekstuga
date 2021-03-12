(ns lekstuga.examples.chat.server
  (:require
   [clojure.core.match :refer [match]]
   [lekstuga.core :as lekstuga]
   [lekstuga.msg :as msg]))

(defn- tell-all!
  [actor-refs msg]
  (doseq [actor-ref actor-refs]
    (lekstuga/tell! actor-ref msg)))

(defn server-actor
  [ctx _props]
  (let [!clients (atom {})]

    (lekstuga/on-cleanup
     ctx (fn []
           (doseq [client (keys @!clients)]
             (lekstuga/unlink! client (lekstuga/self ctx)))))

    (fn [msg]
      (match msg
             {::msg/subj :chat/connect ::msg/from client ::msg/body nick}
             (do
               (swap! !clients assoc client nick)
               (lekstuga/link! client (lekstuga/self ctx))
               (lekstuga/tell! client (-> (lekstuga/msg :chat/connected)
                                       (lekstuga/from (lekstuga/self ctx))))
               (tell-all! (remove #{client} (keys @!clients))
                          (lekstuga/msg :chat/user-connected nick)))
             {::msg/subj :lekstuga/terminated ::msg/from client}
             (let [nick (@!clients client)]
               (swap! !clients dissoc client)
               (tell-all! (keys @!clients)
                          (lekstuga/msg :chat/user-left nick)))
             {::msg/subj :chat/say ::msg/from client ::msg/body s}
             (tell-all! (keys @!clients)
                        (lekstuga/msg :chat/user-says
                                   {:nick (@!clients client)
                                    :text s}))
             {::msg/subj :chat/nick ::msg/from client ::msg/body new-nick}
             (let [old-nick (@!clients client)]
               (swap! !clients assoc client new-nick)
               (tell-all! (remove #{client} (keys @!clients))
                          (lekstuga/msg :chat/nick-changed {:old old-nick :new  new-nick})))
             :else 
             (lekstuga/handle-message! ctx msg)))))
