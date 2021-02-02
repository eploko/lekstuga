(ns eploko.globe
  "A poor man's actor library."
  (:require
   [clojure.string :as str]))

(def no-hop
  "The empty hop denoting the target."
  "")

(def hop-separator
  "The string to separate hops in an address."
  "/")

(def hop-separator-re
  "The regex pattern matching hop separator."
  (re-pattern hop-separator))

(defn- devar
  "Derefs the var if given, return the value otherwise."
  [var-or-val]
  (or (and (var? var-or-val)
           (deref var-or-val))
      var-or-val))

(defn split-hops
  "Splits a hops string into parts."
  [s]
  (str/split s hop-separator-re))

(defn join-hops
  "Joins hops back into a string."
  [hops]
  (str/join hop-separator hops))

(defn first-hop
  "Returns the first hop for the address."
  [addr]
  (first (split-hops addr)))

(defn rest-hops
  "Returns but first hops for the addr."
  [addr]
  (join-hops (rest (split-hops addr))))

(defn make-msg
  "Creates a new message of the given type with the given payload."
  ([msg-type]
   (make-msg msg-type nil))
  ([msg-type payload]
   {:type msg-type
    :payload payload}))

(defn get-msg-type
  "Returns the message's type."
  [msg]
  (:type msg))

(defn get-msg-payload
  "Returns the message's payload."
  [msg]
  (:payload msg))

(defn make-role
  "Returns a new role given a base role and a set of callback handlers
  and a set of message handlers."
  ([handlers]
   (make-role handlers nil))
  ([handlers callbacks]
   (make-role nil handlers callbacks))
  ([base handlers callbacks]
   {:base base
    :callbacks callbacks
    :handlers handlers}))

(defn get-role-base
  "Returns the base role for the role."
  [role]
  (:base role))

(defn get-role-callbacks
  "Returns the role's callbacks."
  [role]
  (:callbacks role))

(defn get-role-handlers
  "Returns the role's handlers."
  [role]
  (:handlers role))

(defn get-role-handler
  "Returns the role's handler for the name."
  [role handler-name]
  (get (get-role-handlers role) handler-name))

(defn get-role-callback
  "Returns the role's callback for the name."
  [role callback-name]
  (get (get-role-callbacks role) callback-name))

(defn make-actor
  "Specifies a new actor."
  ([role name]
   (make-actor role name nil))
  ([role name props]
   {:role role
    :name name
    :props props
    :state (atom nil)
    :mailbox (atom [])
    :children (atom {})}))

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

(defn get-actor-props
  "Returns the actor's props."
  [actor]
  (:props actor))

(defn get-actor-mailbox
  "Returns the actor's mailbox."
  [actor]
  (:mailbox actor))

(defn get-actor-children
  "Returns the actor's children."
  [actor]
  (:children actor))

(defn get-actor-child
  "Returns a deeply nested child."
  [actor addr]
  (if (or (nil? actor)
          (= addr no-hop))
    actor
    (when-let [child (get (deref (get-actor-children actor)) (first-hop addr))]
      (recur child (rest-hops addr)))))

(defn actor-has-child?
  "Checks if the actor has a child at address."
  [actor addr]
  (not (nil? (get-actor-child actor addr))))

(defn add-to-actor-mailbox
  "Adds the message to the actor's mailbox."
  [actor msg]
  (swap! (get-actor-mailbox actor) conj msg))

(defn add-actor-child
  "Adds the child to the actor's children."
  [actor name child]
  (swap! (get-actor-children actor) assoc name child))

(defn spawn
  "Spawns a new actor."
  [role name]
  (make-actor role name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic Actor Role
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- basic-actor-start-h
  [_ctx _state _payload]
  (println "Starting the basic actor!"))

(def ^:private basic-actor-role
  (make-role nil {::start #'basic-actor-start-h}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; System NS Role
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- system-start-h
  [_ctx _state {:keys [main-actor]}]
  (tap> main-actor)
  (println "Starting the system!"))

(def ^:private system-role
  (make-role nil {::start #'system-start-h}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User NS Role
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- user-start-h
  [_ctx _state _payload]
  (println "Starting the user ns!"))

(def ^:private user-role
  (make-role #'basic-actor-role nil {::start #'user-start-h}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private system (atom nil))

(defn make-system
  [name main-actor]
  (make-actor #'system-role name {:main-actor main-actor}))

(defn run-callback
  "Runs the callback with the msg on the actor.
  Returns the updated actor."
  [actor callback msg]
  (callback actor
            (get-actor-state actor)
            (get-msg-payload msg)))

(defn- call
  ([actor msg]
   (let [role (get-actor-role actor)
         msg-type (get-msg-type msg)
         callback (get-role-callback role msg-type)]
     (if callback
       (run-callback actor callback msg)
       (throw (ex-info "No callback. Message lost." {:role role :msg msg}))))))

(defn start-actor
  [actor]
  (call actor (make-msg ::start (get-actor-props actor))))

(defn start-system
  ([main-actor]
   (start-system no-hop main-actor))
  ([root-path main-actor]
   (let [main-actor-name (get-actor-name main-actor)
         new-system (make-system root-path main-actor)]
     (start-actor new-system)
     (reset! system new-system)
     main-actor-name)))

(defn deliver-msg
  "Delivers a message to the actor's mailbox and returns the actor."
  ([actor addr msg]
   (if-let [target (get-actor-child actor addr)]
     (add-to-actor-mailbox target msg)
     (println (str "Unknown address! Message dropped: " {:addr addr :msg msg})))))

(defn tell
  "Sends a message to the actor."
  ([addr msg]
   (tell (deref system) addr msg))
  ([system addr msg]
   (deliver-msg system addr msg)))

(comment
  (defn say-hello-h
    [_ctx _state payload]
    (println (str "Hello " payload ".")))

  (def greeter-role
    (make-role
     {:greet #'say-hello-h}))

  (def my-actor (make-actor #'greeter-role "greeter"))
  (def my-actor-addr (start-system my-actor))
  (get-actor-child (deref system) "greeter")
  (tell my-actor-addr (make-msg :greet "Andrey"))

  (ns-unmap (find-ns 'eploko.globe) 'nested-actor-child-path)
  ,)
