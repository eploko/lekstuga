(ns globe.actor-system
  (:require [clojure.string :as str]
            [globe.actor-registry :as registry]
            [globe.api :as api]))

(defrecord ActorSystem [actor-registry]
  api/Spawner
  (spawn! [_ actor-id actor-fn actor-props]
    (api/spawn! actor-registry actor-id actor-fn actor-props))

  clojure.lang.IFn
  (toString [_]
    (str "<#ActorSystem \"" (api/uri (api/root-guardian actor-registry)) "\">")))

(defmethod print-method ActorSystem
  [o w]
  (print-simple (.toString o) w))

(defn start!
  ([]
   (start! "default"))
  ([system-id]
   (let [ar (registry/local-actor-registry system-id)
         system (map->ActorSystem {:actor-registry ar})]
     (registry/init! ar system)
     system)))

(comment
  (start!)
  (filter #(str/starts-with? % "#'globe.actor-system/")
          (map str
               (vals
                (ns-map (find-ns 'globe.actor-system)))))
  ,)
