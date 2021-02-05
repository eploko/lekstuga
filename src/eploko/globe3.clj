(ns eploko.globe3
  (:require
   [clojure.core.async :as async :refer [<! >! chan go go-loop]]
   [clojure.string :as str]))

(def did-restart-subj ::did-restart)
(def did-stop-subj ::did-stop)
(def init-subj ::init)
(def proxy-subj ::proxy)
(def ^:private reset-state-subj ::reset-state)
(def ^:private set-delivery-mode-subj ::set-delivery-mode)
(def ^:private terminate-subj ::terminate)
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

(defn- emsg
  [mode body]
  [mode body])

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

(defn- mk-delivery-ports
  [mailbox-size]
  {:normal (chan mailbox-size)
   :oob (chan mailbox-size)})

(defn- mk-actor
  [role props]
  (let [delivery-ports (mk-delivery-ports mailbox-size)
        runner-feed-ctrl-port (chan)]
    {:role role
     :props props
     :delivery-ports delivery-ports
     :runner-feed-ctrl-port runner-feed-ctrl-port
     :runner-feed (switched-chan runner-feed-ctrl-port delivery-ports)}))

(defn- get-actor-role
  [actor]
  (:role actor))

(defn- get-actor-props
  [actor]
  (:props actor))

(defn- get-actor-delivery-port
  [actor delivery-mode]
  (get-in actor [:delivery-ports delivery-mode]))

(defn- get-actor-runner-feed
  [actor]
  (:runner-feed actor))

(defn- get-actor-runner-feed-ctrl-port
  [actor]
  (:runner-feed-ctrl-port actor))

(defn- set-actor-delivery-mode!
  [actor delivery-mode]
  (go (>! (get-actor-runner-feed-ctrl-port actor)
          delivery-mode)))

(defn send!
  [actor msg & {:as opts}]
  (go (>! (get-actor-delivery-port actor (get opts :delivery-mode :normal))
          (emsg (get opts :emsg-mode :normal) msg))))

(defn- ctx-actor
  [ctx]
  (:actor ctx))

(defn- unknown-msg-h
  [_ctx state _body]
  (println "Not handled.")
  state)

(defn- resolve-handler
  [role subj]
  (let [handlers (role)]
    (or (get handlers subj)
        (get handlers proxy-subj)
        #'unknown-msg-h)))

(defn- terminate-syscall
  [ctx _state _body]
  (let [actor (ctx-actor ctx)]
    (async/close! (get-actor-delivery-port actor :normal))
    (async/close! (get-actor-delivery-port actor :oob))
    (async/close! (get-actor-runner-feed-ctrl-port actor))
    (prn "terminated")))

(defn- set-delivery-mode-syscall
  [ctx state body]
  (set-actor-delivery-mode! (ctx-actor ctx) body)
  state)

(defn- reset-state-syscall
  [_ctx _state _body]
  nil)

(defn- syscall-role
  []
  {reset-state-subj #'reset-state-syscall
   set-delivery-mode-subj #'set-delivery-mode-syscall
   terminate-subj #'terminate-syscall})

(defn- run-actor!
  [actor]
  (let [role (get-actor-role actor)
        feed (get-actor-runner-feed actor)]
    (go-loop [state nil]
      (when-some [[mode msg] (<! feed)]
        (println ">>" mode msg)
        (let [subj (msg-subj msg)
              body (msg-body msg)
              target-role (case mode
                            :normal role
                            :syscall #'syscall-role)
              h (resolve-handler target-role subj)
              ctx {:actor actor}]
          (recur (h ctx state body)))))))

(defn spawn!
  ([role]
   (spawn! role {}))
  ([role props]
   (let [actor (mk-actor role props)]
     (run-actor! actor)
     (set-actor-delivery-mode! actor :oob)
     (send! actor (mk-msg init-subj props) :delivery-mode :oob)
     (send! actor (mk-msg will-start-subj) :delivery-mode :oob)
     (send! actor (mk-msg set-delivery-mode-subj :normal)
            :delivery-mode :oob :emsg-mode :syscall)
     actor)))

(defn stop!
  [actor]
  (set-actor-delivery-mode! actor :oob)
  (send! actor (mk-msg did-stop-subj) :delivery-mode :oob)
  (send! actor (mk-msg terminate-subj) :delivery-mode :oob :emsg-mode :syscall))

(defn restart!
  [actor]
  (set-actor-delivery-mode! actor :oob)
  (send! actor (mk-msg will-restart-subj) :delivery-mode :oob)
  (send! actor (mk-msg did-stop-subj) :delivery-mode :oob)
  (send! actor (mk-msg reset-state-subj)
         :delivery-mode :oob :emsg-mode :syscall)
  (send! actor (mk-msg init-subj (get-actor-props actor)) :delivery-mode :oob)
  (send! actor (mk-msg will-start-subj) :delivery-mode :oob)
  (send! actor (mk-msg did-restart-subj) :delivery-mode :oob)
  (send! actor (mk-msg set-delivery-mode-subj :normal)
            :delivery-mode :oob :emsg-mode :syscall))

(comment
  (defn init-h
    [_ctx _state body]
    (println "init-h" body)
    body)
  
  (defn greet-h
    [_ctx state body]
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

