(ns lekstuga.actor-registry
  (:require
   [lekstuga.actors :as actors]
   [lekstuga.api :as api]
   [lekstuga.uris :as uris]
   [lekstuga.msg :as msg]
   [lekstuga.refs.bubble :as refs-bubble]))

(defrecord LocalActorRegistry [root-path !init-data]
  api/ActorRegistry
  (root-guardian [_]
    (:root-guardian @!init-data))
  (user-guardian [this]
    (-> (api/root-guardian this)
        (api/resolve-child-ref (uris/child-uri root-path "user"))))
  (temp-guardian [this]
    (-> (api/root-guardian this)
        (api/resolve-child-ref (uris/child-uri root-path "system/temp"))))
  
  api/Spawner
  (spawn! [this actor-id actor-fn actor-props opts]
    (let [user-guardian (api/user-guardian this)
          user-guardian-cell (api/underlying user-guardian)]
      (api/tell! user-guardian (msg/make-signal :lekstuga/new-child))
      (let [child-ref (api/spawn! user-guardian-cell actor-id actor-fn actor-props opts)]
        (api/link! child-ref user-guardian)
        (api/on-cleanup user-guardian-cell #(api/unlink! child-ref user-guardian))
        child-ref)))

  api/ChildRefResolver
  (resolve-child-ref [this str-or-uri]
    (if (uris/child? str-or-uri root-path)
      (-> (api/root-guardian this)
          (api/resolve-child-ref str-or-uri))
      nil)))

(defn local-actor-registry
  [system-id]
  (map->LocalActorRegistry
   {:root-path (uris/system-uri system-id)
    :!init-data (atom nil)}))

(defn init!
  [^LocalActorRegistry this system]
  (let [bubble-ref (refs-bubble/bubble-ref)
        root-path (:root-path this)
        root-guardian (api/local-actor-ref
                       system root-path actors/root-guardian nil bubble-ref
                       {:perform-start false})]
    (reset! (:!init-data this)
            {:system system
             :root-guardian root-guardian})
    (api/start! root-guardian)))
