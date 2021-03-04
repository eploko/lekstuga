(ns globe.actor-system
  (:require
   [clojure.string :as str]
   [globe.async :refer [go-safe]]
   [globe.actor-registry :as registry]
   [globe.api :as api]
   [globe.dispatcher :as dispatcher]
   [globe.mailbox :as mb]
   [globe.refs :as refs]
   [globe.transport :as transport-api]
   [globe.transports.local :as local-transport]))

(defrecord ActorSystem [actor-registry !transports]
  api/ActorSystem
  (registry [this] actor-registry)
  
  api/Spawner
  (spawn! [_ actor-id actor-fn actor-props opts]
    (api/spawn! actor-registry actor-id actor-fn actor-props opts))

  api/ActorRefFactory
  (local-actor-ref [this child-uri actor-fn actor-props supervisor opts]
    (refs/local-actor-ref this child-uri actor-fn actor-props supervisor opts))

  api/MailboxFactory
  (make-mailbox [this]
    (mb/simple-mailbox))

  api/DispatcherFactory
  (dispatcher [this]
    (dispatcher/make-dispatcher))

  clojure.lang.IFn
  (toString [_]
    (str "<#ActorSystem \"" (api/uri (api/root-guardian actor-registry)) "\">"))

  api/RefResolver
  (<resolve-ref! [_ str-or-uri]
    (go-safe
     ))

  api/Transports
  (register-transport! [_ transport]
    (swap! !transports assoc
           (transport-api/get-protocol-name transport)
           transport))
  (get-transport [_ protocol-name]
    (get @!transports protocol-name)))

(defmethod print-method ActorSystem
  [o w]
  (print-simple (.toString o) w))

(defn- init-transports!
  [system]
  (api/register-transport!
   system (local-transport/local-transport "globe")))

(defn start!
  ([]
   (start! "default"))
  ([system-id]
   (let [ar (registry/local-actor-registry system-id)
         system (map->ActorSystem {:actor-registry ar
                                   :!transports (atom {})})]
     (init-transports! system)
     (registry/init! ar system)
     system)))

(comment
  (start!)
  (filter #(str/starts-with? % "#'globe.actor-system/")
          (map str
               (vals
                (ns-map (find-ns 'globe.actor-system)))))
  ,)
