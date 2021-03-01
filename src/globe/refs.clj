(ns globe.refs
  (:require
   [globe.api :as api]
   [globe.cell :as cell]))

(defrecord LocalActorRef [system uri actor-fn actor-props supervisor cell]
  api/Addressable
  (uri [_] uri)
  
  api/MessageTarget
  (tell! [this msg]
    (println "Telling:" msg))

  api/ActorRefWithCell
  (underlying [_] cell)

  api/Startable
  (start! [this]
    ;; TODO: WRITE ME
    )

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
  (let [cell (cell/make-cell system actor-fn actor-props supervisor)
        inst (map->LocalActorRef
              {:system system
               :uri uri
               :actor-fn actor-fn
               :actor-props actor-props
               :supervisor supervisor
               :cell cell})]
    (cell/init! cell inst)
    inst))

(comment
  (local-actor-ref)
  ,)
