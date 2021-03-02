(ns globe.msg)

(defn make-msg
  ([subj]
   {::subj subj})
  ([subj body]
   {::subj subj ::body body}))
