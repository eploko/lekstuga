(ns globe.refs
  (:require
   [globe.api :as api]
   [globe.msg :as msg]
   [globe.cell :as cell]))

(defrecord LocalActorRef [system uri actor-fn actor-props supervisor cell
                          mailbox dispatcher]
  api/Addressable
  (uri [_] uri)
  
  api/MessageTarget
  (tell! [this msg]
    (api/put! mailbox msg))

  api/ActorRefWithCell
  (underlying [_] cell)
  (register-death! [this]
    (api/tell! supervisor (-> (msg/make-signal :globe/child-terminated)
                              (msg/from this))))

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
               :dispatcher (api/dispatcher system)})]
    (api/put! mailbox (msg/make-signal :globe/create))
    (cell/init! cell inst)
    (api/start! inst)
    inst))

(comment
  (local-actor-ref)
  ,)
