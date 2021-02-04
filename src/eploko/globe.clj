(ns eploko.globe
  (:require
   [clojure.core.async :as async :refer [<!]]
   [clojure.string :as str]))

(defn devar
  "Derefs the var if given, return the value otherwise."
  [var-or-val]
  (or (and (var? var-or-val)
           (deref var-or-val))
      var-or-val))

(defn queue
  ([] (clojure.lang.PersistentQueue/EMPTY))
  ([coll]
   (reduce conj clojure.lang.PersistentQueue/EMPTY coll)))

(defn noop
  [])

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
    :ops (atom (queue))
    :new-op-ch (async/chan)
    :state (atom nil)
    :mailbox (atom (queue))
    :new-mail-ch (atom nil)
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

(defn get-actor-ops
  [actor]
  (:ops actor))

(defn get-actor-new-op-ch
  [actor]
  (:new-op-ch actor))

(defn get-actor-state
  [actor]
  (:state actor))

(defn reset-actor-state
  [actor]
  (reset! (get-actor-state actor) nil))

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
  (deref (:new-mail-ch actor)))

(defn alog
  [actor & args]
  (println (apply str "[" (get-actor-addr actor) "] " args)))

(defn enqueu
  [actor get-queue-fn get-trigger-ch-fn x]
  (swap! (get-queue-fn actor) conj x)
  (async/put! (get-trigger-ch-fn actor) true))

(defn enqueue-op
  [actor f & args]
  (enqueu actor get-actor-ops get-actor-new-op-ch
          (apply partial f actor args)))

(defn enqueue-msg
  [actor msg]
  (enqueu actor get-actor-mailbox get-actor-new-mail-ch msg))

(defn init-actor-new-mail-ch
  [actor]
  (reset! (:new-mail-ch actor) (async/chan)))

(defn close-actor-new-mail-ch
  [actor]
  (async/close! (get-actor-new-mail-ch actor))
  (reset! (:new-mail-ch actor) nil))

(defn close-actor-new-op-ch
  [actor]
  (async/close! (get-actor-new-op-ch actor)))

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
  (when-let [addr (get-actor-parent-addr actor)]
    (resolve-addr addr)))

(defn deliver-msg
  [actor msg]
  (binding [*current-actor* actor]
    (try
      (swap! (get-actor-state actor)
             apply-actor-h
             (get-actor-role actor)
             msg)
      (catch Exception e
        (alog "failed with: " e)))))

(defn fetch-from-queue
  [trigger-ch !mailbox]
  (async/go
    (when (<! trigger-ch)
      (let [msg (peek @!mailbox)]
        (swap! !mailbox pop)
        msg))))

(defn run-queue
  [trigger-ch q f {:keys [on-start on-finish]
                   :or {on-start noop
                        on-finish noop}}]
  (on-start)
  (async/go-loop []
    (if-let [msg (<! (fetch-from-queue trigger-ch q))]
      (do
        (f msg)
        (recur))
      (on-finish))))

(defn start-postman
  [actor]
  (run-queue
   (init-actor-new-mail-ch actor)
   (get-actor-mailbox actor)
   (partial enqueue-op actor deliver-msg)
   {:on-start (fn [] (alog actor "postman started."))
    :on-finish (fn [] (alog actor "postman stopped."))}))

(defn stop-postman
  [actor]
  (close-actor-new-mail-ch actor))

(defn start-ops-runner
  [actor]
  (run-queue
   (get-actor-new-op-ch actor)
   (get-actor-ops actor)
   (fn [op] (op))
   {:on-start (fn [] (alog actor "ops runner started."))
    :on-finish (fn [] (alog actor "ops runner stopped."))}))

(defn stop-ops-runner
  [actor]
  (close-actor-new-op-ch actor))

(defn start-actor
  [actor]
  (enqueue-op actor (fn [actor] (alog actor "starting...")))
  (enqueue-op actor deliver-msg (make-msg ::init (get-actor-props actor)))
  (enqueue-op actor deliver-msg (make-msg ::will-start))
  (enqueue-op actor start-postman))

(defn stop-actor
  [actor]
  (enqueue-op actor (fn [actor] (alog actor "stopping...")))
  (enqueue-op actor stop-postman)
  (enqueue-op actor deliver-msg (make-msg ::did-stop)))

(defn restart-actor
  [actor]
  (enqueue-op actor (fn [actor] (alog actor "restarting...")))
  (enqueue-op actor deliver-msg (make-msg ::will-restart))
  (stop-actor actor)
  (enqueue-op actor reset-actor-state)
  (start-actor actor)
  (enqueue-op actor deliver-msg (make-msg ::did-restart)))

(defn- spawn-in-parent!
  [parent role child-name props]
  (let [child-addr (if parent
                     (join-addrs (get-actor-addr parent) child-name)
                     system-addr)
        new-actor (make-actor role child-addr props)]
    (if parent
      (add-actor-child parent new-actor)
      (reset! system new-actor))
    (start-ops-runner new-actor)
    (start-actor new-actor)
    child-addr))

(defn assert-current-actor!
  [f-name m]
  (when-not *current-actor*
    (throw
     (ex-info
      (str "`" f-name "` can only be called in a message handler!")
      m))))

(defn spawn!
  ([role child-name]
   (spawn! role child-name nil))
  ([role child-name props]
   (assert-current-actor! "spawn!" {:role role :name child-name})
   (spawn-in-parent! *current-actor* role child-name props)))

(defn- terminate-actor
  [actor]
  (stop-ops-runner actor)
  (if-let [parent (get-actor-parent actor)]
    (remove-actor-child parent actor)
    (reset! system nil)))

(defn stop!
  ([]
   (assert-current-actor! "stop!" {})
   (stop! *current-actor*))
  ([actor]
   (stop-actor actor)
   (enqueue-op actor terminate-actor)))

(defn super
  [state msg]
  (assert-current-actor! "super" {:state state :msg msg})
  (if-let [base (get-role-base *current-role*)]
    (apply-actor-h state base msg)
    state))

(defn dummy-h
  [state _msg]
  state)

(def base-init-h dummy-h)
(def base-will-start-h dummy-h)
(def base-did-stop-h dummy-h)
(def base-will-restart-h dummy-h)
(def base-did-restart-h dummy-h)

(def base-handler-not-found-h default-h)

(defn base-stop-h
  [state _msg]
  (stop!)
  state)

(def actor-role
  (make-role
   {::did-restart #'base-did-restart-h
    ::did-stop #'base-did-stop-h
    ::handler-not-found #'base-handler-not-found-h
    ::init #'base-init-h
    ::stop #'base-stop-h
    ::will-restart #'base-will-restart-h
    ::will-start #'base-will-start-h}))

(defn derive-role
  ([handlers]
   (make-role #'actor-role handlers))
  ([base handlers]
   (make-role base handlers)))

(defn user-ns-init-h
  [state msg]
  (let [new-state (super state msg)
        {:keys [main-actor-role main-actor-name main-actor-props]} (get-msg-payload msg)]
    (spawn! main-actor-role main-actor-name main-actor-props)
    new-state))

(def user-ns-role
  (derive-role
   {::init #'user-ns-init-h}))

(defn system-init-h
  [state msg]
  (let [new-state (super state msg)]
    (spawn! #'user-ns-role
            "user"
            (select-keys (get-msg-payload msg)
                         [:main-actor-role :main-actor-name :main-actor-props]))
    new-state))

(def system-role
  (derive-role
   {::init #'system-init-h}))

(defn start-system
  ([role name]
   (start-system role name nil))
  ([role name props]
   (spawn-in-parent! nil #'system-role ""
                     {:main-actor-role role
                      :main-actor-name name
                      :main-actor-props props})
   (join-addrs "/user" name)))

(defn stop-system
  []
  (stop! (deref system)))

(defn tell
  [to msg]
  (if-let [actor (resolve-addr to)]
    (enqueue-msg actor msg)
    (println (str "No actor at address: "
                  to
                  " Message dropped: "
                  msg))))

(comment
  (defn greeter-init-h
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
     {::init #'greeter-init-h
      :fail #'greeter-fail-h
      :greet #'greeter-greet-h}))
  
  (def my-actor-ref (start-system #'greeter-role "greeter" {:greeting "Hello"}))
  (tell my-actor-ref (make-msg :greet "Yorik"))
  (tell my-actor-ref (make-msg ::stop))
  (tell my-actor-ref (make-msg ::restart))
  (tell my-actor-ref (make-msg :fail))

  (tap> my-actor-ref)
  (tap> system)

  (stop-system)

  (find-suitable-handler system-role ::will-start)

  (ns-unmap (find-ns 'eploko.globe2) 'restart!)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'eploko.globe2/")
          (map str
               (vals
                (ns-map (find-ns 'eploko.globe2)))))
  ,)
