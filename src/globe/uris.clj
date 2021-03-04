(ns globe.uris
  (:require
   [lambdaisland.uri :as luri :refer [uri]]
   [clojure.string :as str]))

(def prefix-uri (uri "globe://"))

(defn system-uri
  [system-id]
  (str (assoc prefix-uri
              :host system-id)))

(defn child-uri
  [parent child-name]
  (let [parent-uri (luri/uri parent)]
    (luri/join parent-uri
               (str (:path parent-uri) "/" child-name))))

(defn address
  [str-or-uri]
  (let [x (uri str-or-uri)]
    (str (assoc x :path ""))))

(defn child?
  [str-or-uri parent-str-or-uri]
  (str/starts-with? (str str-or-uri) (str parent-str-or-uri)))

(defn segments
  [path]
  (str/split path #"/"))

(defn child-name
  ([str-or-uri]
   (let [x (uri str-or-uri)
         path (:path x)]
     (when path
       (last (segments path)))))
  ([parent-str-or-uri str-or-uri]
   (let [parent-str (str parent-str-or-uri
                         (when-not (str/ends-with? parent-str-or-uri "/")
                           "/"))
         target-str (str str-or-uri)]
     (-> (subs target-str (count parent-str))
         segments
         first))))

(defn same?
  [a b]
  (= (str a) (str b)))

(comment
  (address "glo")
  (system-uri "aloha")
  (child? (luri/join (system-uri "aloha") "/" "user")
          (system-uri "aloha"))
  (child-uri "globe://host/user" "next")
  (child-name (system-uri "aloha"))
  (child-name (system-uri "aloha")
              (luri/join (system-uri "aloha") "/user/whoa"))

  (str (luri/join (system-uri "aloha") "/user/whoa"))
  (subs "aloha" (count "al"))
  ,)
