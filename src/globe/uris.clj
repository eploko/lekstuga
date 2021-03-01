(ns globe.uris
  (:require
   [lambdaisland.uri :as luri :refer [uri]]))

(def prefix-uri (uri "globe://"))

(defn system-uri
  [system-id]
  (str (assoc prefix-uri
              :user system-id
              :host "localhost")))

(defn child-uri
  [parent child-name]
  (let [parent-uri (luri/uri parent)]
    (luri/join parent-uri
               (str (:path parent-uri) "/" child-name))))

(comment
  (system-uri "aloha")
  (child-uri "globe://host/user" "next")
  ,)
