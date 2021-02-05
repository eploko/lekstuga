(ns eploko.globe3
  (:require
   [clojure.core.async :as async :refer [<! >! chan go go-loop]]
   [clojure.string :as str]))

(def mailbox-size 10)

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
  [role props]
  {:role role
   :props props
   :port (chan mailbox-size)})

(defn- get-actor-role
  [actor]
  (:role actor))

(defn- get-actor-props
  [actor]
  (:props actor))

(defn- get-actor-port
  [actor]
  (:port actor))

(defn- unknown-msg-h
  [state _body]
  state)

(defn- resolve-handler
  [role subj]
  (let [handlers (role)]
    (or (get handlers subj)
        (get handlers ::proxy)
        #'unknown-msg-h)))

(defn enqueu-op!
  [actor op-f]
  (go (>! (get-actor-port actor) (partial op-f actor))))

(defn- msg-op
  [msg actor state]
  (let [subj (msg-subj msg)
        body (msg-body msg)
        role (get-actor-role actor)
        h (resolve-handler role subj)]
    (h state body)))

(defn- start-op
  [actor state]
  (->> state
       (msg-op (mk-msg ::init (get-actor-props actor)) actor)
       (msg-op (mk-msg ::will-start) actor)))

(defn- stop-op
  [actor state]
  (msg-op (mk-msg ::did-stop) actor state)
  (async/close! (get-actor-port actor)))

(defn- restart-op
  [actor state]
  (->> state
       (msg-op (mk-msg ::will-restart) actor)
       (msg-op (mk-msg ::did-stop) actor))
  (->> nil
       (msg-op (mk-msg ::init (get-actor-props actor)) actor)
       (msg-op (mk-msg ::will-start) actor)
       (msg-op (mk-msg ::did-restart) actor)))

(defn- run-actor!
  [actor]
  (go-loop [state nil]
    (when-some [op-f (<! (get-actor-port actor))]
      (recur (op-f state)))))

(defn send!
  [actor msg]
  (enqueu-op! actor (partial msg-op msg)))

(defn spawn!
  ([role]
   (spawn! role {}))
  ([role props]
   (let [actor (mk-actor role props)]
     (run-actor! actor)
     (enqueu-op! actor start-op)
     actor)))

(defn stop!
  [actor]
  (enqueu-op! actor stop-op))

(defn restart!
  [actor]
  (enqueu-op! actor restart-op))

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
    {::init #'init-h
     :greet #'greet-h})

  (def greeter-addr (spawn! #'greeter {:greeting "Hej"}))
  (send! greeter-addr (mk-msg :greet "Andrey"))
  (stop! greeter-addr)
  (restart! greeter-addr)

  (ns-unmap (find-ns 'eploko.globe3) '*current-actor*)
  ,)

