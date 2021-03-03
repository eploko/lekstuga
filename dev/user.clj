(ns user
  (:require
   [clojure.core.match :refer [match]]
   [globe.core :as globe]
   [globe.msg :as msg]
   [clojure.core.async :as async :refer [<! go]]
   [clojure.string :as str]))

(comment
  (defn my-hero
    [ctx _props]
    (globe/log! (globe/self ctx) "Initialising...")
    (partial globe/handle-message! ctx))

  (defn greeter
    [ctx greeting]
    (globe/log! (globe/self ctx) "Initialising...")
    (let [state (atom 0)]
      (globe/spawn! ctx "my-hero" my-hero nil)

      (fn [msg]
        (match msg
               {::msg/subj :greet ::msg/body who}
               (println (format "%s %s!" greeting who))
               {::msg/subj :wassup?}
               (globe/reply! msg "WASSUP!!!")
               {::msg/subj :throw}
               (throw (ex-info "Something went wrong!" {:reason :requested}))
               {::msg/subj :inc}
               (let [x (swap! state inc)]
                 (globe/log! (globe/self ctx) "X:" x))
               :else (globe/handle-message! ctx msg)))))

  (def system (globe/start-system!))
  (def main-actor (globe/spawn! system "greeter" greeter "Hello"))

  (globe/tell! main-actor (globe/msg :greet "Andrey"))
  (globe/tell! main-actor (globe/msg :inc))
  (go (println "reply:"
               (<! (globe/<ask! main-actor (globe/msg :wassup?)))))
  (globe/tell! main-actor (globe/msg :globe/poison-pill))
  (globe/tell! main-actor (globe/msg :throw))

  ;; helpers

  (tap> main-actor)
  
  (ns-unmap *ns* 'registry)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'user/")
          (map str
               (vals
                (ns-map (find-ns 'user)))))
  ,)

