(ns user
  (:require [clojure.core.match :refer [match]]
            [globe.core :as globe]))


(comment
  (defn receive!
    [ctx msg])

  (defn reply!
    [ctx msg])
  
  (defn my-hero
    [ctx]
    (globe/log! (::self ctx) "Initialising...")
    (partial receive! ctx))

  (defn greeter
    [greeting ctx]
    (globe/log! (::self ctx) "Initialising...")
    (let [state (atom 0)]
      (globe/spawn! ctx "my-hero" my-hero)

      (fn [msg]
        (match [msg]
               [{:subj :greet :body who}]
               (println (format "%s %s!" greeting who))
               [{:subj :wassup?}]
               (reply! msg "WASSUP!!!")
               [{:subj :throw}]
               (throw (ex-info "Something went wrong!" {:reason :requested}))
               [{:subj :inc}]
               (let [x (swap! state inc)]
                 (globe/log! (::self ctx) "X:" x))
               :else (receive! ctx msg)))))

  (def system (globe/start-system!))
  (def main-actor (globe/spawn! system "greeter" greeter "Hello"))
  (type main-actor)

  (globe/tell! main-actor (globe/msg :greet "Andrey"))
  (globe/tell! main-actor (globe/msg :inc))
  (go (println "reply:"
               (<! (<ask! main-actor {:subj :wassup?}))))
  (tell! main-actor {:subj ::poison-pill})
  (tell! main-actor {:subj :throw})

  ;; helpers

  (tap> main-actor)
  
  (ns-unmap (find-ns 'globe.core) 'log)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'eploko.globe5/")
          (map str
               (vals
                (ns-map (find-ns 'eploko.globe5)))))
  ,)

