(ns lekstuga.actor-system
  (:require
   [clojure.string :as str]
   [cognitect.anomalies :as anom]
   [lekstuga.async :refer [<? go-safe]]
   [lekstuga.actor-registry :as registry]
   [lekstuga.api :as api]
   [lekstuga.api.transport :as transport-api]
   [lekstuga.dispatcher :as dispatcher]
   [lekstuga.mailbox :as mb]
   [lekstuga.refs :as refs]
   [lekstuga.transports.local :as local-transport]
   [lekstuga.uris :as uris]))

(defrecord ActorSystem [system-name actor-registry !transports]
  api/HasName
  (get-name [this] system-name)
  
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
     (let [scheme (-> str-or-uri uris/scheme)]
       (if-let [transport (@!transports scheme)]
         (<? (api/<resolve-ref! transport str-or-uri))
         {::anom/category ::anom/not-found
          ::anom/message (str "No transport registered for scheme: " scheme)
          :data str-or-uri}))))

  api/Transports
  (register-transport! [_ transport]
    (swap! !transports assoc
           (transport-api/scheme transport)
           transport)
    (api/start! transport))
  (get-transport [_ protocol-name]
    (get @!transports protocol-name))

  api/Startable
  (start! [this]
    (registry/init! actor-registry this)
    (api/register-transport!
     this (local-transport/local-transport "lekstuga" this))
    this)
  (stop! [this]
    (doseq [transport (vals @!transports)]
      (api/stop! transport))))

(defmethod print-method ActorSystem
  [o w]
  (print-simple (.toString o) w))

(defn start!
  ([]
   (start! "default"))
  ([system-name]
   (api/start!
    (map->ActorSystem
     {:system-name system-name
      :actor-registry (registry/local-actor-registry system-name)
      :!transports (atom {})}))))

(defn stop!
  [system]
  (api/stop! system))

(comment
  (start!)
  (filter #(str/starts-with? % "#'lekstuga.actor-system/")
          (map str
               (vals
                (ns-map (find-ns 'lekstuga.actor-system)))))
  ,)
