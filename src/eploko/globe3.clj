(ns eploko.globe3
  (:require
   [clojure.core.async :as async :refer [<! >! chan go go-loop]]
   [clojure.string :as str]))

(def did-restart-subj ::did-restart)
(def did-stop-subj ::did-stop)
(def init-subj ::init)
(def proxy-subj ::proxy)
(def will-restart-subj ::will-restart)
(def will-start-subj ::will-start)

(defn mk-msg
  ([subj]
   (mk-msg subj nil))
  ([subj body]
   {:subj subj
    :body body}))

(defn- msg-subj
  [msg]
  (:subj msg))

(defn- msg-body
  [msg]
  (:body msg))

(defn- mk-actor
  [role]
  {:role role
   :msg-port (chan)})

(defn- get-actor-role
  [actor]
  (:role actor))

(defn- get-actor-msg-port
  [actor]
  (:msg-port actor))

(defn send!
  [actor msg]
  (go (>! (get-actor-msg-port actor) msg)))

(defn- unknown-msg-h
  [state _body]
  (println "Not handled.")
  state)

(defn- resolve-handler
  [role subj]
  (let [handlers (role)]
    (or (get handlers subj)
        (get handlers proxy-subj)
        #'unknown-msg-h)))

(defn- run-actor!
  [actor]
  (let [role (get-actor-role actor)
        msg-port (get-actor-msg-port actor)]
    (go-loop [state nil]
      (if-let [msg (<! msg-port)]
        (let [subj (msg-subj msg)
              body (msg-body msg)
              h (resolve-handler role subj)
              new-state (h state body)]
          (recur new-state))
        (prn "terminated")))))

(defn spawn!
  ([role]
   (spawn! role {}))
  ([role props]
   (let [actor (mk-actor role)]
     (run-actor! actor)
     (go (send! actor (mk-msg init-subj props))
         (send! actor (mk-msg will-start-subj)))
     actor)))

(defn stop!
  [actor]
  (go (send! actor (mk-msg did-stop-subj))
      (async/close! (get-actor-msg-port actor))))

(defn restart!
  [actor]
  (go (send! actor (mk-msg will-restart-subj))
      (send! actor (mk-msg did-stop-subj))
      ;; reset state somehow
      ;; get props somehow
      #_(send! actor (mk-msg init-subj props))
      (send! actor (mk-msg will-start-subj))
      (send! actor (mk-msg did-restart-subj))))

(comment
  (defn init-h
    [_state body]
    body)
  
  (defn greet-h
    [state body]
    (println (str (:greeting state) " " body "!"))
    state)
  
  (defn greeter
    []
    {init-subj #'init-h
     :greet #'greet-h})

  (def greeter-addr (spawn! #'greeter {:greeting "Hej"}))
  (send! greeter-addr (msg :greet "Andrey"))
  (stop! greeter-addr)
  (restart! greeter-addr)

  (ns-unmap (find-ns 'eploko.globe3) 'msg)
  ,)

