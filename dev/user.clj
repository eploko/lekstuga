(ns user
  (:require
   [clojure.core.match :refer [match]]
   [lekstuga.core :as lekstuga]
   [lekstuga.logger :refer [debug info]]
   [lekstuga.msg :as msg]
   [clojure.core.async :as async :refer [<! go]]
   [clojure.string :as str]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.tools.logging :refer [use-timbre]]))

(defn configure-timbre!
  []
  (use-timbre)
  (let [level->str
        {:trace "T"
         :debug "D"
         :info  "I"
         :warn  "W"
         :error "E"
         :fatal "F"
         :report "R"}]
    (timbre/merge-config! 
     {:timestamp-opts 
      {:pattern ""}
      :output-fn
      (fn [{:keys [level vargs_]}]
        (let [event (apply str (force vargs_))]
          (str (get level->str level level) " " event)))})))

(configure-timbre!)

(comment
  (defn my-hero
    [ctx _props]
    (debug (lekstuga/self ctx) "Initialising...")
    (partial lekstuga/handle-message! ctx))

  (defn greeter
    [ctx greeting]
    (debug (lekstuga/self ctx) "Initialising...")
    (let [state (atom 0)]
      (lekstuga/spawn! ctx "my-hero" my-hero nil nil)

      (fn [msg]
        (match msg
               {::msg/subj :greet ::msg/body who}
               (println (format "%s %s!" greeting who))
               {::msg/subj :wassup?}
               (lekstuga/reply! msg "WASSUP!!!")
               {::msg/subj :throw}
               (throw (ex-info "Something went wrong!" {:reason :requested}))
               {::msg/subj :inc}
               (let [x (swap! state inc)]
                 (info (lekstuga/self ctx) "X:" x))
               :else (lekstuga/handle-message! ctx msg)))))

  (def system (lekstuga/start-system!))
  (def main-actor (lekstuga/spawn! system "greeter" greeter "Hello" nil))

  (lekstuga/tell! main-actor (lekstuga/msg :greet "Andrey"))
  (lekstuga/tell! main-actor (lekstuga/msg :inc))
  (go (println "reply:"
               (<! (lekstuga/<ask! main-actor (lekstuga/msg :wassup?)))))
  (lekstuga/tell! main-actor (lekstuga/msg :lekstuga/poison-pill))
  (lekstuga/tell! main-actor (lekstuga/msg :throw))

  ;; helpers

  (tap> main-actor)
  
  (ns-unmap *ns* 'registry)

  ;; all names in the ns
  (filter #(str/starts-with? % "#'user/")
          (map str
               (vals
                (ns-map (find-ns 'user)))))
  ,)

