(ns eploko.globe2)

(defn devar
  "Derefs the var if given, return the value otherwise."
  [var-or-val]
  (or (and (var? var-or-val)
           (deref var-or-val))
      var-or-val))

(declare ^:dynamic *current-actor*)
(declare ^:dynamic *current-role*)
(defonce system (atom nil))

(defn make-msg
  ([kind]
   (make-msg kind nil))
  ([kind payload]
   {:kind kind
    :payload payload}))

(defn get-msg-kind
  [msg]
  (:kind msg))

(defn get-msg-payload
  [msg]
  (:payload msg))

(defn default-h
  [state msg]
  (println (str "No handler. Message dropped: " msg))
  state)

(defn make-role
  ([handlers]
   (make-role nil handlers))
  ([base handlers]
   {:base base
    :handlers handlers}))

(defn get-role-base
  [role]
  (devar (:base role)))

(defn get-role-immediate-handlers
  [role]
  (:handlers role))

(defn get-role-immediate-handler
  [role kind]
  (-> (get-role-immediate-handlers role)
      (get kind)))

(defn get-role-handler
  ([role kind]
   (get-role-handler role role kind))
  ([original-role current-role kind]
   (when current-role
     (if-let [h (get-role-immediate-handler current-role kind)]
       h
       (recur original-role (get-role-base current-role) kind)))))

(defn find-suitable-handler
  [role kind]
  (or (get-role-handler role kind)
      (get-role-handler role ::handler-not-found)
      default-h))

(defn make-actor
  [role actor-name]
  {:role role
   :name actor-name
   :state (atom nil)})

(defn get-actor-role
  [actor]
  (devar (:role actor)))

(defn get-actor-state
  [actor]
  (:state actor))

(defn apply-actor-h
  [state role msg]
  (let [kind (get-msg-kind msg)
        h (find-suitable-handler role kind)]
    (binding [*current-role* role]
      (h state msg))))

(defn deliver-msg
  [actor msg]
  (binding [*current-actor* actor]
    (swap! (get-actor-state actor)
           apply-actor-h
           (get-actor-role actor)
           msg)))

(defn start-actor
  [actor]
  (deliver-msg actor (make-msg ::start)))

(defn super
  [state msg]
  (when-not *current-role*
    (throw (ex-info "`super` can only be called in a message handler!"
                    {:state state :msg msg})))
  (if-let [base (get-role-base *current-role*)]
    (apply-actor-h state base msg)
    state))

(defn base-start-h
  [state _msg]
  (println "Starting...")
  (assoc state ::started? true))

(def base-handler-not-found-h default-h)

(def actor-role
  (make-role
   {::start #'base-start-h
    ::handler-not-found #'base-handler-not-found-h}))

(defn derive-role
  ([handlers]
   (make-role #'actor-role handlers))
  ([base handlers]
   (make-role base handlers)))

(defn system-start-h
  [state msg]
  (let [new-state (super state msg)]
    (println "Starting system...")
    (println (str "new state: " new-state))
    new-state))

(def system-role
  (derive-role
   {::start #'system-start-h}))

(defn spawn
  [role name])

(defn start-system
  [role name]
  (let [new-system (make-actor #'system-role "")]
    (reset! system new-system)
    (start-actor new-system)
    name))

(comment
  (defn greeter-greet-h
    [state payload]
    (println (str "Hello " payload "!"))
    state)
  
  (def greeter-role
    (derive-role
     {:greet #'greeter-greet-h}))
  
  (def my-actor-ref (start-system #'greeter-role "greeter"))

  (tap> system)

  (find-suitable-handler system-role ::start)

  (ns-unmap (find-ns 'eploko.globe2) 'perform-cb)
  ,)
