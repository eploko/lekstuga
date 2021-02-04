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

(defn msg
  ([subj]
   (msg subj nil))
  ([subj body]
   {:subj subj
    :body body}))

(defn- msg-subj
  [msg]
  (:subj msg))

(defn- msg-body
  [msg]
  (:body msg))

(defn- unknown-msg-h
  [_state _payload]
  (println "Not handled."))

(defn- resolve-handler
  [actor subj]
  (let [handlers (actor)]
    (or (get handlers subj)
        (get handlers proxy-subj)
        #'unknown-msg-h)))

(defn- run-actor!
  [actor]
  (let [in-port (chan)]
      (go-loop [state nil]
        (if-let [msg (<! in-port)]
          (let [subj (msg-subj msg)
                h (resolve-handler actor subj)
                body (msg-body msg)]
            (recur (h state body)))
          (prn "terminated")))
      in-port))

(defn spawn!
  ([actor]
   (spawn! actor {}))
  ([actor props]
   (let [actor-port (run-actor! actor)]
     (go (>! actor-port (msg init-subj props))
         (>! actor-port (msg will-start-subj)))
     actor-port)))

(defn stop!
  [addr]
  (go (>! addr (msg did-stop-subj))
      (async/close! addr)))

(defn restart!
  [addr]
  (go (>! addr (msg will-restart-subj))
      (>! addr (msg did-stop-subj))
      ;; reset state somehow
      ;; get props somehow
      #_(>! addr (msg init-subj props))
      (>! addr (msg will-start-subj))
      (>! addr (msg did-restart-subj))))

(comment
  (defn init-h
    [_state body]
    body)
  
  (defn greet-h
    [state body]
    (println (str (:greeting state) " " body "!")))
  
  (defn greeter
    []
    {init-subj #'init-h
     :greet #'greet-h})

  (def greeter-addr (spawn! #'greeter {:greeting "Hej"}))
  (go (>! greeter-addr (msg :greet "Andrey")))
  (stop! greeter-addr)
  (restart! greeter-addr)

  (ns-unmap (find-ns 'eploko.globe3) 'msg-payload)
  ,)

