(ns globe.api)

(defprotocol ActorSystem
  (registry [this] "Returns an `ActorRegistry`"))

(defprotocol MailboxFactory
  (make-mailbox [this] "Makes a new mailbox."))

(defprotocol Spawner
  "Allows spawning other actors as children of `this`."
  (spawn! [this actor-id actor-fn actor-props opts]
    "Spawns a new child actor."))

(defprotocol ActorRefFactory
  (local-actor-ref [this child-uri actor-fn actor-props supervisor opts]
    "Creates a new actor ref."))

(defprotocol ActorRegistry
  "Keeps track of main actors in the system and creates new refs."
  (root-guardian [this] "Returns a ref to the root guardian.")
  (user-guardian [this] "Returns a ref to the user guardian.")
  (temp-guardian [this] "Returns a ref to the temp guardian."))

(defprotocol ActorRefResolver
  (resolve-actor-ref [this str-or-uri] "Returns the actor ref."))

(defprotocol Startable
  (start! [this] "Start this cell, i.e. attach it to the dispatcher."))

(defprotocol Terminatable
  (terminate! [this] "Terminates the process."))

(defprotocol Children
  (add-child! [this child-ref on-failure])
  (remove-child! [this child-ref])
  (get-child-ref [this child-name]))

(defprotocol Supervisor
  (supervising-strategy [this child-ref]
    "Returns the supervising strategy for the child ref."))

(defprotocol MessageTarget
  "An entity able to receive messages."
  (tell! [this msg]
    "Accepts the message `msg` for processing."))

(defprotocol Addressable
  (uri [this] "Returns the URI.")
  (get-name [this] "Returns the last segment of the underlying URI."))

(defprotocol PartOfTree
  (self [this] "Returns the self ref.")
  (supervisor [this] "Returns the ref to the supervisor."))

(defprotocol HasSystem
  (system [this] "Returns the underlying system."))

(defprotocol ActorRefWithCell
  (underlying [this] "Returns the underlying actor cell.")
  (register-death! [this] "Handles death of the cell."))

(defprotocol Suspendable
  (suspend! [this])
  (resume! [this]))

(defprotocol Mailbox
  (put! [this msg]))

(defprotocol Dispatcher
  (start-dispatching! [this mailbox cell])
  (stop-dispatching! [this]))

(defprotocol DispatcherFactory
  (dispatcher [this] "Returns a dispatcher."))

(defprotocol MessageHandler
  (handle-message! [this msg] "Handles the given message."))

(defprotocol UnhandledMessageHandler
  (handle-unhandled-message! [this msg]
    "Figures out what to do w/ an unhandled message."))

(defprotocol HasBehavior
  (become! [this behavior-fn] "Sets a new behavior."))

(defprotocol HasMode
  (switch-to-mode! [this mode] "Switches to the mode."))

(defprotocol Linkable
  (link! [this link])
  (unlink! [this link]))

(defprotocol WithLifeCycleHooks
  (on-cleanup [this f]))

(defprotocol LifeCycle
  (cleanup! [this])
  (restart! [this])
  (init! [this]))
