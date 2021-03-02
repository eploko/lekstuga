(ns globe.api)

(defprotocol MailboxFactory
  (make-mailbox [this] "Makes a new mailbox."))

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

(defprotocol Terminatable
  (terminate! [this] "Terminates the process."))

(defprotocol Children
  (add-child! [this child-ref])
  (remove-child! [this child-ref]))

(defprotocol MessageTarget
  "An entity able to receive messages."
  (tell! [this msg]
    "Accepts the message `msg` for processing."))

(defprotocol Addressable
  (uri [this] "Returns the URI."))

(defprotocol HasSelf
  (self [this] "Returns the self ref."))

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
