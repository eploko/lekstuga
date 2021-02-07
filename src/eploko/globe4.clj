(ns eploko.globe4
  (:require
   [clojure.core.async :as async :refer [<! >! chan go go-loop]]
   [clojure.core.async.impl.protocols :as async-protocols]))

(deftype UnboundBuffer [!buf]
  async-protocols/Buffer
  (full? [this]
    false)
  (remove! [this]
    (ffirst (swap-vals! !buf pop)))
  (add!* [this itm]
    (swap! !buf conj itm))
  (close-buf! [this] (reset! !buf []))
  clojure.lang.Counted
  (count [this]
    (count @!buf))
  clojure.lang.IDeref
  (deref [this]
    @!buf))

(defn unbound-buf
  []
  (UnboundBuffer. (atom [])))

(defonce ^:private !addrs (atom {}))

(defn- reg-addr!
  [addr port]
  (swap! !addrs assoc addr port))

(defn- unreg-addr!
  [addr]
  (swap! !addrs dissoc addr))

(defn- join-addr
  [addr child]
  (str addr "/" child))

(defn- resolve-addr
  [addr]
  (get @!addrs addr))

(defn msg
  ([to subj body]
   (msg nil to subj body))
  ([from to subj body]
   {:from from
    :to to
    :subj subj
    :body body}))

(defn- msg-from
  [msg]
  (:from msg))

(defn- msg-to
  [msg]
  (:to msg))

(defn- msg-subj
  [msg]
  (:subj msg))

(defn- msg-body
  [msg]
  (:body msg))

(defn send!
  [msg]
  (if-let [port (resolve-addr (msg-to msg))]
    (go (>! port msg))
    (println "No such address:" (msg-to msg) "Message dropped:" msg)))

(defn <query!
  ([to subj]
   (<query! to subj nil))
  ([to subj body]
   (let [q-addr "query"
         in (chan)]
     (reg-addr! q-addr in)
     (send! (msg q-addr to subj body))
     (go (let [reply-msg (<! in)]
           (unreg-addr! q-addr)
           (msg-body reply-msg))))))

(defn spawn!
  [parent-addr actor-name actor-fn]
  (let [addr (join-addr parent-addr actor-name)]
    (reg-addr! addr (actor-fn addr))
    addr))

(defn- mk-ctx
  [self from]
  {:self self
   :from from
   :next (atom nil)})

(defn ctx-self
  [ctx]
  (:self ctx))

(defn ctx-from
  [ctx]
  (:from ctx))

(defn- ctx-next
  ([ctx]
   (deref (:next ctx)))
  ([ctx v]
   (reset! (:next ctx) v)))

(defn ctx-stop
  [ctx]
  (ctx-next ctx ::stopped))

(defn- actor-stop-h
  [ctx state _subj _body]
  (ctx-stop ctx)
  state)

(def ^:private internal-handlers
  {::stop #'actor-stop-h})

(defn- find-handler
  [subj handlers not-found]
  (get handlers subj not-found))

(defn- <stopped-behavior
  [self port role _constructor state]
  (let [cleanup (:cleanup role)]
    (go
      (async/close! port)
      (cleanup state)
      (println "Actor stopped:" self)
      nil)))

(defn- <default-behavior
  [self port role constructor state]
  (let [cleanup (:cleanup role)]
    (go 
      (when-some [msg (<! port)]
        (try
          (let [ctx (mk-ctx self (msg-from msg))
                subj (msg-subj msg)
                handler (find-handler subj internal-handlers (:handle role))
                new-state (handler ctx state subj (msg-body msg))]  
            (case (ctx-next ctx)
              ::stopped (do (unreg-addr! self)
                            [<stopped-behavior new-state])
              [<default-behavior new-state]))
          (catch Exception e
            (println "exception:" e "actor will restart:" self)
            (cleanup state)
            [<default-behavior (constructor)]))))))

(def ^:private default-role
  {:init (fn [_props])
   :cleanup (fn [_state])
   :handle (fn [_ctx _subj _body])})

(defn actor
  [& {:as role}]
  (let [realized-role (merge default-role role)]
    (fn [props]
      (fn [self]
        (let [port (chan (unbound-buf))
              init-f (:init realized-role)
              constructor (partial init-f self props)]
          (go-loop [behavior <default-behavior
                    state (constructor)]
            (when-some [[next-behavior next-state]
                        (<! (behavior self port realized-role constructor state))]
              (recur next-behavior next-state)))
          port)))))

(defn reply!
  [ctx a]
  (send! (msg (ctx-self ctx) (ctx-from ctx) ::reply a)))

(comment
  ;; TODO: Spawn system
  ;; TODO: Spawn children in an actor
  ;; TODO: Make sure children are stopped when the parent is stopped or restarted
  ;; TODO: Let supervisor decide what to do with a failed actor
  (defn greeter-init
    [_self props]
    (println "Initializing...")
    {:x 0
     :greeting props})

  (defn greeter-cleanup
    [_state]
    (println "I will restart."))
  
  (defn greeter-handle
    [ctx state subj body]
    (case subj
      :wassup? (do (reply! ctx "Wassup!") state)
      :greet (do (println (str (:greeting state) " " body "!")) state)
      :inc (update state :x inc)
      :state? (do (reply! ctx state) state)
      :fail! (throw (ex-info "Woohoo!" {}))
      state))
  
  (def greeter
    (actor
     :init #'greeter-init
     :cleanup #'greeter-cleanup
     :handle #'greeter-handle))

  (def greeter-addr (spawn! nil "greeter" (greeter "Hello")))
  (send! (msg greeter-addr :greet "Andrey"))
  (send! (msg greeter-addr :inc nil))
  (go 
    (println "state:" (<! (<query! greeter-addr :state?))))
  (go 
    (println "response:" (<! (<query! greeter-addr :wassup?))))
  (send! (msg greeter-addr ::stop nil))
  (send! (msg greeter-addr :fail! nil))

  (tap> @!addrs)
  (go (>! (resolve-addr "query") (msg "query" nil "yo")))
  (unreg-addr! "query")

  (ns-unmap (find-ns 'eploko.globe4) 'addr!)
  ,)
