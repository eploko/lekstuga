(ns globe.refs
  (:require
   [globe.api :as api]
   [globe.msg :as msg]
   [globe.cell :as cell]))

(defrecord LocalActorRef [system uri actor-fn actor-props supervisor cell
                          mailbox dispatcher !links !dead?]
  api/Addressable
  (uri [_] uri)
  
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
         ">")))

(defmethod print-method LocalActorRef
  [o w]
  (print-simple (.toString o) w))

(defn local-actor-ref
  [system uri actor-fn actor-props supervisor]
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
    (api/start! inst)
    inst))

(comment
  (local-actor-ref)
  ,)
