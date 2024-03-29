(ns lekstuga.mailbox
  (:require
   [lekstuga.api :as api]
   [lekstuga.async :as gasync]
   [lekstuga.msg :as msg]
   [clojure.core.async :as async])
  (:import
   [clojure.lang IDeref]))

(defrecord SimpleMailbox [signals messages !suspended?]
  api/Mailbox
  (put! [_ msg]
    (async/put!
     (if (::msg/signal? msg) signals messages)
     msg))

  api/Suspendable
  (suspend! [_]
    (reset! !suspended? true))
  (resume! [_]
    (reset! !suspended? false))

  api/Startable
  (start! [_])
  (stop! [this]
    (async/close! signals)
    (async/close! messages))

  IDeref
  (deref [_]
    (if @!suspended?
      [signals]
      [signals messages])))

(defn simple-mailbox
  []
  (map->SimpleMailbox
   {:signals (async/chan (gasync/unbound-buf))
    :messages (async/chan (gasync/unbound-buf))
    :!suspended? (atom true)}))
