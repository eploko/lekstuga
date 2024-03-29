(ns lekstuga.core
  (:require
   [lekstuga.actor-system :as system]
   [lekstuga.api :as api]
   [lekstuga.ask :as ask]
   [lekstuga.msg :as msg]))

(def start-system! system/start!)
(def stop-system! system/stop!)
(def spawn! api/spawn!)
(def tell! api/tell!)
(def <ask! ask/<ask!)
(def reply! ask/reply!)
(def msg msg/make-msg)
(def from msg/from)
(def self api/self)
(def handle-message! api/handle-message!)
(def on-cleanup api/on-cleanup)
(def link! api/link!)
(def unlink! api/unlink!)
(def become! api/become!)
(def <resolve-ref! api/<resolve-ref!)

