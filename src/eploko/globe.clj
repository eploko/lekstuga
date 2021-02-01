(ns eploko.globe
  "A poor man's actor library."
  (:require
   [clojure.string :as str]))

(defn- devar
  "Derefs the var if given, return the value otherwise."
  [var-or-val]
  (or (and (var? var-or-val)
           (deref var-or-val))
      var-or-val))

(defn make-msg
  "Creates a new message of the given type with the given payload."
  ([msg-type]
   (make-msg msg-type nil))
  ([msg-type payload]
   [msg-type payload]))

(defn get-msg-type
  "Returns the message's type."
  [msg]
  (first msg))

(defn get-msg-payload
  "Returns the message's payload."
  [msg]
  (second msg))

(defn make-actor
  "Specifies a new actor."
  ([role name]
   (make-actor role name nil))
  ([role name props]
   {:role role
    :name name
    :state props
    :mailbox []
    :children {}}))

(defn get-actor-role
  "Returns the actor's role."
  [actor]
  (devar (:role actor)))

(defn get-actor-name
  "Returns the actor's name."
  [actor]
  (:name actor))

(defn get-actor-state
  "Returns the actor's state."
  [actor]
  (:state actor))

(defn get-actor-mailbox
  "Returns the actor's mailbox."
  [actor]
  (:mailbox actor))

(defn get-actor-children
  "Returns the actor's children."
  [actor]
  (:children actor))

(defn add-to-actor-mailbox
  "Adds the message to the actor's mailbox."
  [actor msg]
  (update actor :mailbox conj msg))

(defn add-actor-child
  "Adds the child to the actor's children."
  [actor name child]
  (update actor :children assoc name child))

(defn spawn
  "Spawns a new actor."
  [role name]
  (make-actor role name))

(defn- system-start-h
  [state _payload]
  (println "Starting the system!")
  state)

(def ^:private system-role
  {::start #'system-start-h})

(defn make-system
  [name]
  (make-actor #'system-role name))

(defn register-actor
  [registry name actor]
  (assoc registry name actor))

(defonce ^:private system (atom nil))

(defn- call
  ([actor msg]
   (let [role (get-actor-role actor)
         msg-type (get-msg-type msg)
         h (get role msg-type)]
     (if h 
       (update actor :state h (get-msg-payload msg))
       (throw (ex-info "No handler!" {:role role :msg-type msg-type}))))))

(defn start-system
  ([user-actor]
   (start-system "" user-actor))
  ([root-path user-actor]
   (let [actor-name (get-actor-name user-actor)]
     (reset! system (make-system root-path))
     (swap! system call (make-msg ::start))
     (swap! system update :children
            register-actor actor-name user-actor)
     actor-name)))

(defn addr-parts
  "Splits an addr into a tuple of head and rest."
  [addr]
  (let [parts (str/split addr #"/" 2)]
    [(first parts)
     (str/join "/" (rest parts))]))

(defn deliver-msg
  "Delivers a message to the actor's mailbox and returns the actor."
  [actor addr msg]
  (if (= "" addr)
    (add-to-actor-mailbox actor msg)
    (let [[head tail] (addr-parts addr)]
      (update-in actor [:children head] deliver-msg tail msg))))

(defn tell
  "Sends a message to the actor."
  ([addr msg]
   (tell system addr msg))
  ([system addr msg]
   (swap! system deliver-msg addr msg)))

(comment
  (defn say-hello-h
    [state payload]
    (println (str "Hello " payload ".")))

  (def greeter-role
    {:greet #'say-hello-h})

  (def my-actor (make-actor #'greeter-role "greeter"))
  (def my-actor-addr (start-system my-actor))

  (tell my-actor-addr (make-msg :greet "Andrey"))

  (say-hello-h {})

  (ns-unmap (find-ns 'eploko.globe) 'my-system)
  ,)
