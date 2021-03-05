(ns examples.chat.server
  (:require
   [clojure.core.match :refer [match]]
   [globe.core :as globe]
   [globe.msg :as msg]))

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

