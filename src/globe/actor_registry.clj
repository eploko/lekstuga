(ns globe.actor-registry
  (:require
   [globe.actors :as actors]
   [globe.api :as api]
   [globe.uris :as uris]))

(defrecord LocalActorRegistry [root-path !init-data]
  api/ActorRegistry
  (root-guardian [this]
    (:root-guardian @!init-data))
  
  api/Spawner
  (spawn! [this actor-id actor-fn actor-props]
    (let [user-guardian (:user-guardian @!init-data)
          user-guardian-cell (api/underlying user-guardian)]
      (api/spawn! user-guardian-cell actor-id actor-fn actor-props))))

(defn local-actor-registry
  [system-id]
  (map->LocalActorRegistry
   {:root-path (uris/system-uri system-id)
    :!init-data (atom nil)}))

(defn init!
  [^LocalActorRegistry this system]
  (let [root-path (:root-path this)
        root-guardian (api/local-actor-ref system root-path actors/root-guardian nil nil)
        root-cell (api/underlying root-guardian)
        user-path (uris/child-uri root-path "user")
        user-guardian (api/local-actor-ref system user-path actors/user-guardian nil root-guardian)
        system-path (uris/child-uri root-path "system")
        system-guardian (api/local-actor-ref system system-path actors/system-guardian nil root-guardian)]
    (reset! (:!init-data this)
            {:system system
             :root-guardian root-guardian
             :user-guardian user-guardian
             :system-guardian system-guardian})
    (api/add-child! root-cell user-guardian)
    (api/start! user-guardian)
    (api/add-child! root-cell system-guardian)
    (api/start! system-guardian)))
