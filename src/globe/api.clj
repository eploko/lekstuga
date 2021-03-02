(ns globe.api)

(defprotocol Spawner
  "Allows spawning other actors as children of `this`."
  (spawn! [this actor-id actor-fn actor-props]
    "Spawns a new child actor."))

(defprotocol ActorRefFactory
  (local-actor-ref [this child-uri actor-fn actor-props supervisor]
    "Creates a new actor ref."))

(defprotocol ActorRegistry
  "Keeps track of main actors in the system and creates new refs."
  (root-guardian [this] "Returns a ref to the root guardian."))

(defprotocol Startable
  (start! [this] "Start this cell, i.e. attach it to the dispatcher."))

(defprotocol Children
  (add-child! [this child-ref]))

(defprotocol MessageTarget
  "An entity able to receive messages."
  (tell! [this msg]
    "Accepts the message `msg` for processing."))

(defprotocol Addressable
  (uri [this] "Returns the URI."))

(defprotocol ActorRefWithCell
  (underlying [this] "Returns the underlying actor cell."))
