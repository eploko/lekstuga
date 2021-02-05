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
        (get handlers proxy-subj)
        #'unknown-msg-h)))

(defn enqueu-op!
  [actor op-f]
  (go (>! (get-actor-port actor) op-f)))

(defn- msg-op
  [actor msg state]
  (let [subj (msg-subj msg)
        body (msg-body msg)
        role (get-actor-role actor)
        h (resolve-handler role subj)]
    (h state body)))

(defn- spawn-op
  [actor state]
  (->> state
       (msg-op actor (mk-msg init-subj (get-actor-props actor)))
       (msg-op actor (mk-msg will-start-subj))))

(defn- stop-op
  [actor state]
  (msg-op actor (mk-msg did-stop-subj) state)
  (async/close! (get-actor-port actor)))

(defn- restart-op
  [actor state]
  (->> state
       (msg-op actor (mk-msg will-restart-subj))
       (msg-op actor (mk-msg did-stop-subj)))
  (->> nil
       (msg-op actor (mk-msg init-subj (get-actor-props actor)))
       (msg-op actor (mk-msg will-start-subj))
       (msg-op actor (mk-msg did-restart-subj))))

(defn send!
  [actor msg]
  (enqueu-op! actor (partial msg-op actor msg)))

(defn- run-actor!
  [actor]
  (go-loop [state nil]
    (when-some [op-f (<! (get-actor-port actor))]
      (recur (op-f state)))))

(defn spawn!
  ([role]
   (spawn! role {}))
  ([role props]
   (let [actor (mk-actor role props)]
     (run-actor! actor)
     (enqueu-op! actor (partial spawn-op actor))
     actor)))

(defn stop!
  [actor]
  (enqueu-op! actor (partial stop-op actor)))

(defn restart!
  [actor]
  (enqueu-op! actor (partial restart-op actor)))

(comment
  (defn init-h
    [_state body]
    (println "init-h" body)
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
  (send! greeter-addr (mk-msg :greet "Andrey"))
  (stop! greeter-addr)
  (restart! greeter-addr)

  (ns-unmap (find-ns 'eploko.globe3) '*current-actor*)
  ,)

