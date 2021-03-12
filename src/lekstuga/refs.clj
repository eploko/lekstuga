(ns lekstuga.refs
  (:require
   [lekstuga.api :as api]
   [lekstuga.msg :as msg]
   [lekstuga.cell :as cell]
   [lekstuga.uris :as uris]))

(defrecord LocalActorRef [system uri actor-fn actor-props supervisor cell
                          mailbox dispatcher !links !dead?]
  api/Addressable
  (uri [_] uri)

  api/HasName
  (get-name [_] (uris/child-name uri))

  api/HasSystem
  (system [this] system)
  
  api/MessageTarget
  (tell! [this msg]
    (api/put! mailbox msg))

  api/ActorRefWithCell
  (underlying [_] cell)
  (register-death! [this]
    (reset! !dead? true)
    (api/tell! supervisor (-> (msg/make-signal :lekstuga/child-terminated)
                              (msg/from this)))
    (doseq [link (first (swap-vals! !links #{}))]
      (api/tell! link (-> (msg/make-msg :lekstuga/terminated)
                          (msg/from this)))))

  api/Startable
  (start! [this]
    (api/start-dispatching! dispatcher mailbox cell))
  (stop! [this]
    (api/stop-dispatching! dispatcher)
    (api/stop! mailbox)
    (api/cleanup-actor! cell)
    (api/register-death! this))

  api/Suspendable
  (suspend! [this]
    (api/suspend! mailbox))
  (resume! [this]
    (api/resume! mailbox))

  api/Linkable
  (link! [this link]
    (if @!dead?
      (api/tell! link (-> (msg/make-msg :lekstuga/terminated)
                          (msg/from this)))
      (swap! !links conj link)))
  
  (unlink! [this link]
    (swap! !links disj link))

  clojure.lang.IFn
  (toString [_]
    (str "<#LocalActorRef \"" uri "\" "
         "fn: " actor-fn ", "
         "props: " actor-props
         ">"))

  api/ChildRefResolver
  (resolve-child-ref [this str-or-uri]
    (if (uris/same? str-or-uri uri)
      this
      (if (uris/child? str-or-uri uri)
        (let [cell (api/underlying this)
              child-name (uris/child-name uri str-or-uri)]
          (when-let [child-ref (api/get-child-ref cell child-name)]
            (api/resolve-child-ref child-ref str-or-uri)))
        nil))))

(defmethod print-method LocalActorRef
  [o w]
  (print-simple (.toString o) w))

(defn local-actor-ref
  [system uri actor-fn actor-props supervisor
   {:keys [perform-start]
    :or {perform-start true}}]
  (let [mailbox (api/make-mailbox system)
        cell (cell/make-cell system actor-fn actor-props supervisor)
        inst (map->LocalActorRef
              {:system system
               :uri uri
               :actor-fn actor-fn
               :actor-props actor-props
               :supervisor supervisor
               :cell cell
               :mailbox mailbox
               :dispatcher (api/dispatcher system)
               :!links (atom #{})
               :!dead? (atom false)})]
    (cell/init! cell inst)
    (when perform-start (api/start! inst))
    inst))

(comment
  (local-actor-ref)
  ,)
