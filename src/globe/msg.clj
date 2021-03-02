(ns globe.msg)

(defn make-msg
  ([subj]
   {::subj subj})
  ([subj body]
   {::subj subj ::body body}))

(defn- turn-to-signal
  [msg]
  (assoc msg ::signal? true))

(defn make-signal
  ([subj]
   (-> (make-msg subj) turn-to-signal))
  ([subj body]
   (-> (make-msg subj body) turn-to-signal)))

(defn from
  [msg sender]
  (assoc msg ::from sender))
