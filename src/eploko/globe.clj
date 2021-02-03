(ns eploko.globe2
  (:require
   [clojure.core.async :as async :refer [<!]]
   [clojure.string :as str]))

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

(def system-addr "/")
(def hop-separator "/")
(def hop-separator-re (re-pattern hop-separator))
(def root-hop "")

(defn root-hop?
  [hop]
  (= root-hop hop))

(defn system-addr?
  [addr]
  (= addr system-addr))

(defn absolute-addr?
  [addr]
  (str/starts-with? addr "/"))

(defn addr-hops
  [addr]
  (if (system-addr? addr)
    [""]
    (str/split addr hop-separator-re)))

(comment
  (addr-hops "/")
  (addr-hops "")
  (addr-hops "/user")
  (addr-hops "/user/greeter")
  (addr-hops "user/greeter")
  ,)

(defn join-hops
  [hops]
  (str/join hop-separator hops))

(comment
  (join-hops ["" "one" "two"])
  ,)

(defn first-addr-hop
  [addr]
  (first (addr-hops addr)))

(defn last-addr-hop
  [addr]
  (last (addr-hops addr)))

(defn join-addrs
  [a b]
  (join-hops
   (concat (addr-hops a)
           (remove #{""} (addr-hops b)))))

(comment
  (join-addrs "user" "greeter")
  (join-addrs "/user" "greeter")
  (join-addrs "/user" "/greeter")
  ,)

(defn addr-relative-to
  [parent a]
  (let [parent-hops (addr-hops parent)
        a-hops (addr-hops a)]
    (if (= (seq parent-hops) (take (count parent-hops) a-hops))
      (join-hops (drop (count parent-hops) a-hops))
      (throw (ex-info "Addresses are not related!" {:parent parent :a a})))))

(comment
  (addr-relative-to "/" "/user")
  (addr-relative-to "/user/eee" "/user/eee/some/other")
  ,)

(defn parent-addr
  [addr]
  (let [hops (addr-hops addr)]
    (when (< 1 (count hops))
      (let [parent-hops (butlast hops)]
        (if (and (= 1 (count parent-hops))
                 (root-hop? (first parent-hops)))
          hop-separator
          (join-hops parent-hops))))))

(comment
  (parent-addr "")
  (parent-addr "/")
  (parent-addr "/user")
  (parent-addr "user")
  (parent-addr "one/two")
  (parent-addr "/one/two")
  ,)

(defn make-actor
  ([role addr]
   (make-actor role addr nil))
  ([role addr props]
   {:role role
    :addr addr
    :props props
    :state (atom nil)
    :mailbox (atom [])
    :new-mail-ch (async/chan)
    :children (atom {})}))

(defn get-actor-role
  [actor]
  (devar (:role actor)))

(defn get-actor-addr
  [actor]
  (:addr actor))

(defn get-actor-parent-addr
  [actor]
  (parent-addr (get-actor-addr actor)))

(defn get-actor-name
  [actor]
  (last-addr-hop (get-actor-addr actor)))

(defn get-actor-state
  [actor]
  (:state actor))

(defn get-actor-props
  [actor]
  (:props actor))

(defn get-actor-children
  [actor]
  (:children actor))

(defn get-actor-child
  [actor child-name]
  (get (deref (get-actor-children actor)) child-name))

(defn get-actor-mailbox
  [actor]
  (:mailbox actor))

(defn get-actor-new-mail-ch
  [actor]
  (:new-mail-ch actor))

(defn add-actor-child
  [actor child-actor]
  (swap! (get-actor-children actor)
         assoc (get-actor-name child-actor) child-actor))

(defn remove-actor-child
  [actor child-actor]
  (swap! (get-actor-children actor)
         dissoc (get-actor-name child-actor)))

(defn apply-actor-h
  [state role msg]
  (let [kind (get-msg-kind msg)
        h (find-suitable-handler role kind)]
    (binding [*current-role* role]
      (h state msg))))

(defn find-child-at
  [actor addr]
  (let [relative-addr (addr-relative-to (get-actor-addr actor) addr)
        child-name (first-addr-hop relative-addr)]
    (when-let [child (get-actor-child actor child-name)]
      (if (= addr (get-actor-addr child))
        child
        (recur child addr)))))

(defn resolve-addr
  [addr]
  (if (system-addr? addr)
    (deref system)
    (find-child-at (deref system) addr)))

(comment
  (first-addr-hop "user")
  (addr-relative-to "/" "/user")
  (resolve-addr "/")
  (resolve-addr "/user")
  (resolve-addr "/user/greeter")
  (resolve-addr "/non-existent")
  ,)

(defn get-actor-parent
  [actor]
  (resolve-addr (get-actor-parent-addr actor)))

(defn deliver-msg
  [actor msg]
  (binding [*current-actor* actor]
    (swap! (get-actor-state actor)
           apply-actor-h
           (get-actor-role actor)
           msg)))

(defn fetch-msg
  [trigger-ch !mailbox]
  (async/go
    (when (<! trigger-ch)
      (let [msg (first @!mailbox)]
        (swap! !mailbox subvec 1)
        msg))))

(defn run-postman
  [actor new-mail-ch mailbox]
  (println (str "Postman [" actor "] started." ))
  (async/go-loop []
    (if-let [msg (<! (fetch-msg new-mail-ch mailbox))]
      (do 
        (deliver-msg actor msg)
        (recur))
      (println (str "Postman [" actor "] stopped." )))))

(defn start-postman
  [actor]
  (run-postman actor
               (get-actor-new-mail-ch actor)
               (get-actor-mailbox actor)))

(defn stop-postman
  [actor]
  (async/close! (get-actor-new-mail-ch actor)))

(defn start-actor
  [actor]
  (deliver-msg actor (make-msg ::will-start (get-actor-props actor)))
  (start-postman actor))

(defn stop-actor
  [actor]
  (println (str "Stopping actor:" actor))
  (stop-postman actor)
  (deliver-msg actor (make-msg ::did-stop)))

(defn spawn!
  ([role child-name]
   (spawn! role child-name nil))
  ([role child-name props]
   (when-not *current-actor*
     (throw (ex-info "`spawn` can only be called in a message handler!" {:role role :name child-name})))
   (let [child-addr (join-addrs (get-actor-addr *current-actor*) child-name)
         new-actor (make-actor role child-addr props)]
     (add-actor-child *current-actor* new-actor)
     (start-actor new-actor))))

(defn stop!
  ([]
   (when-not *current-actor*
     (throw (ex-info "`stop!` can only be called in a message handler!" {})))
   (stop! *current-actor*))
  ([actor]
   (stop-actor actor)
   (remove-actor-child (get-actor-parent actor) actor)))

(defn super
  [state msg]
  (when-not *current-role*
    (throw (ex-info "`super` can only be called in a message handler!"
                    {:state state :msg msg})))
  (if-let [base (get-role-base *current-role*)]
    (apply-actor-h state base msg)
    state))

(defn base-will-start-h
  [state _msg]
  state)

(defn base-did-stop-h
  [state _msg]
  state)

(defn base-stop-h
  [state _msg]
  (stop!)
  state)

(def base-handler-not-found-h default-h)

(def actor-role
  (make-role
   {::did-stop #'base-did-stop-h
    ::stop #'base-stop-h
    ::will-start #'base-will-start-h
    ::handler-not-found #'base-handler-not-found-h}))

(defn derive-role
  ([handlers]
   (make-role #'actor-role handlers))
  ([base handlers]
   (make-role base handlers)))

(defn user-ns-will-start-h
  [state msg]
  (let [new-state (super state msg)
        {:keys [main-actor-role main-actor-name main-actor-props]} (get-msg-payload msg)]
    (spawn! main-actor-role main-actor-name main-actor-props)
    new-state))

(def user-ns-role
  (derive-role
   {::will-start #'user-ns-will-start-h}))

(defn system-will-start-h
  [state msg]
  (let [new-state (super state msg)]
    (spawn! #'user-ns-role
            "user"
            (select-keys (get-msg-payload msg)
                         [:main-actor-role :main-actor-name :main-actor-props]))
    new-state))

(def system-role
  (derive-role
   {::will-start #'system-will-start-h}))

(defn start-system
  ([role name]
   (start-system role name nil))
  ([role name props]
   (let [new-system (make-actor #'system-role
                                system-addr
                                {:main-actor-role role
                                 :main-actor-name name
                                 :main-actor-props props})]
     (reset! system new-system)
     (start-actor new-system)
     (join-addrs "/user" name))))

(defn add-msg-to-mailbox
  [mailbox msg]
  (swap! mailbox conj msg))

(defn trigger-delivery
  [actor]
  (let [ch (get-actor-new-mail-ch actor)]
    (async/put! ch true)))

(defn tell
  [to msg]
  (if-let [actor (resolve-addr to)]
    (let [mailbox (get-actor-mailbox actor)]
      (add-msg-to-mailbox mailbox msg)
      (trigger-delivery actor))
    (println (str "No actor at address: "
                  to
                  " Message dropped: "
                  msg))))

(comment
  (defn greeter-will-start-h
    [state msg]
    (let [{:keys [greeting]} (get-msg-payload msg)]
      (-> (super state msg)
          (assoc :greeting greeting))))

  (defn greeter-fail-h
    [_state _]
    (throw (ex-info "Greeter failed!" {})))
  
  (defn greeter-greet-h
    [state msg]
    (println (str (:greeting state) " " (get-msg-payload msg) "!"))
    state)
  
  (def greeter-role
    (derive-role
     {::will-start #'greeter-will-start-h
      :fail #'greeter-fail-h
      :greet #'greeter-greet-h}))
  
  (def my-actor-ref (start-system #'greeter-role "greeter" {:greeting "Hello"}))
  (tell my-actor-ref (make-msg :greet "Yorik"))
  (tell my-actor-ref (make-msg ::stop))
  (tell my-actor-ref (make-msg :fail))

  (tap> system)

  (find-suitable-handler system-role ::will-start)

  (ns-unmap (find-ns 'eploko.globe2) 'next-hop-addr)
  ,)
