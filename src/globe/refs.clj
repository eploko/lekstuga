(ns globe.refs
  (:require
   [globe.api :as api]
   [globe.msg :as msg]
   [globe.cell :as cell]
   [globe.uris :as uris]))

(defrecord LocalActorRef [system uri actor-fn actor-props supervisor cell
                          mailbox dispatcher !links !dead?]
  api/Addressable
  (uri [_] uri)
  (get-name [_] (uris/child-name uri))
  
  api/MessageTarget
  (tell! [this msg]
    (api/put! mailbox msg))

  api/ActorRefWithCell
  (underlying [_] cell)
  (register-death! [this]
    (reset! !dead? true)
    (api/tell! supervisor (-> (msg/make-signal :globe/child-terminated)
                              (msg/from this)))
    (doseq [link (first (swap-vals! !links #{}))]
      (api/tell! link (-> (msg/make-msg :globe/terminated)
                          (msg/from this)))))

  api/Startable
  (start! [this]
    (api/start-dispatching! dispatcher mailbox cell))

  api/Suspendable
  (suspend! [this]
    (api/suspend! mailbox))
  (resume! [this]
    (api/resume! mailbox))

  api/Terminatable
  (terminate! [this]
    (api/stop-dispatching! dispatcher)
    (api/terminate! mailbox)
    (api/register-death! this))

  api/Linkable
  (link! [this link]
    (if @!dead?
      (api/tell! link (-> (msg/make-msg :globe/terminated)
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

  api/ActorRefResolver
  (resolve-actor-ref [this str-or-uri]
    (if (uris/same? str-or-uri uri)
      this
      (if (uris/child? str-or-uri uri)
        (let [cell (api/underlying this)
              child-name (uris/child-name uri str-or-uri)]
          (when-let [child-ref (api/get-child-ref cell child-name)]
            (api/resolve-actor-ref child-ref str-or-uri)))
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
    (api/put! mailbox (msg/make-signal :globe/create))
    (cell/init! cell inst)
    (when perform-start (api/start! inst))
    inst))

(comment
  (local-actor-ref)
  ,)
