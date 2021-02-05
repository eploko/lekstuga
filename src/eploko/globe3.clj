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

(defn- toggle-switched-chan-ports
  "Switches the mix to be a solo of the channel under `solo-k`."
  [mix ports-map solo-k]
  (println "switching mix to mode" solo-k)
  (async/toggle mix
                (reduce-kv (fn [accu k v] (assoc accu v {:pause (not= k solo-k)}))
                           {}
                           ports-map)))

(defn switched-chan
  "Creates a channel switching between channels in `ports-map`.
  `ports-map` is a map of keys to channels. You put keys onto
  the `ctrl-port` to switch the resulting channel. The resulting
  channel is closed when `ctrl-port` closes. Initially every
  channel in `ports-map` is paused."
  [ctrl-port ports-map]
  (let [out-port (chan)
        mix (async/mix out-port)]
    (toggle-switched-chan-ports mix ports-map nil)
    (go-loop []
      (if-some [k (<! ctrl-port)]
        (do
          (toggle-switched-chan-ports mix ports-map k)
          (recur))
        (async/close! out-port)))
    out-port))

(def ^:private actor-mode-msg :msg)
(def ^:private actor-mode-ctrl :ctrl)

(defn- mk-actor
  [role props]
  (let [msg-port (chan mailbox-size)
        ctrl-port (chan mailbox-size)
        runner-feed-ctrl-port (chan)]
    {:role role
     :props props
     :msg-port msg-port
     :ctrl-port ctrl-port
     :runner-feed-ctrl-port runner-feed-ctrl-port
     :runner-feed (switched-chan runner-feed-ctrl-port
                                 {actor-mode-msg msg-port
                                  actor-mode-ctrl ctrl-port})}))

(defn- get-actor-role
  [actor]
  (:role actor))

(defn- get-actor-props
  [actor]
  (:props actor))

(defn- get-actor-msg-port
  [actor]
  (:msg-port actor))

(defn- get-actor-ctrl-port
  [actor]
  (:ctrl-port actor))

(defn- get-actor-runner-feed
  [actor]
  (:runner-feed actor))

(defn- get-actor-runner-feed-ctrl-port
  [actor]
  (:runner-feed-ctrl-port actor))

(defn- set-actor-mode!
  [actor mode]
  (go (>! (get-actor-runner-feed-ctrl-port actor)
          mode)))

(defn send!
  [actor msg]
  (go (>! (get-actor-msg-port actor) msg)))

(defn ctrl!
  [actor msg]
  (go (>! (get-actor-ctrl-port actor) msg)))

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

(defn- terminate-actor!
  [actor]
  (async/close! (get-actor-msg-port actor))
  (async/close! (get-actor-ctrl-port actor))
  (async/close! (get-actor-runner-feed-ctrl-port actor))
  (prn "terminated"))

(defn- run-actor!
  [actor]
  (let [role (get-actor-role actor)
        feed (get-actor-runner-feed actor)]
    (go-loop [state nil]
      (when-some [msg (<! feed)]
        (let [subj (msg-subj msg)
              body (msg-body msg)
              h (resolve-handler role subj)
              new-state (h state body)]
          (recur new-state))))))

(defn spawn!
  ([role]
   (spawn! role {}))
  ([role props]
   (let [actor (mk-actor role props)]
     (run-actor! actor)
     (set-actor-mode! actor actor-mode-ctrl)
     (ctrl! actor (mk-msg init-subj props))
     (ctrl! actor (mk-msg will-start-subj))
     (set-actor-mode! actor actor-mode-msg)
     actor)))

(defn stop!
  [actor]
  (set-actor-mode! actor actor-mode-ctrl)
  (ctrl! actor (mk-msg did-stop-subj))
  (terminate-actor! actor))

(defn restart!
  [actor]
  (set-actor-mode! actor actor-mode-ctrl)
  (ctrl! actor (mk-msg will-restart-subj))
  (ctrl! actor (mk-msg did-stop-subj))
  ;; reset state somehow
  (ctrl! actor (mk-msg init-subj (get-actor-props actor)))
  (ctrl! actor (mk-msg will-start-subj))
  (ctrl! actor (mk-msg did-restart-subj))
  (set-actor-mode! actor actor-mode-msg))

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
  (send! greeter-addr (mk-msg :greet "Andrey"))
  (stop! greeter-addr)
  (restart! greeter-addr)

  (ns-unmap (find-ns 'eploko.globe3) 'mk-msg-mix!)
  ,)

