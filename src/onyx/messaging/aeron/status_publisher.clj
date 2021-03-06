(ns onyx.messaging.aeron.status-publisher
  (:require [onyx.messaging.protocols.status-publisher :as status-pub]
            [onyx.peer.constants :refer [UNALIGNED_SUBSCRIBER]]
            [onyx.types :as t]
            [onyx.messaging.serialize :as sz]
            [onyx.compression.nippy :refer [messaging-compress messaging-decompress]]
            [onyx.messaging.aeron.utils :as autil :refer [action->kw stream-id heartbeat-stream-id]]
            [taoensso.timbre :refer [debug info warn] :as timbre])
  (:import [org.agrona.concurrent UnsafeBuffer]
           [org.agrona ErrorHandler]
           [io.aeron Aeron Aeron$Context Publication]))

(deftype StatusPublisher [peer-config peer-id dst-peer-id site ^Aeron conn ^Publication pub 
                          ^:unsynchronized-mutable blocked ^:unsynchronized-mutable completed
                          ^:unsynchronized-mutable short-id ^:unsynchronized-mutable session-id 
                          ^:unsynchronized-mutable heartbeat]
  status-pub/PStatusPublisher
  (start [this]
    (let [media-driver-dir (:onyx.messaging.aeron/media-driver-dir peer-config)
          status-error-handler (reify ErrorHandler
                                 (onError [this x] 
                                   (taoensso.timbre/warn x "Aeron status channel error")))
          ctx (cond-> (Aeron$Context.)
                error-handler (.errorHandler status-error-handler)
                media-driver-dir (.aeronDirectoryName ^String media-driver-dir))
          channel (autil/channel (:address site) (:port site))
          conn (Aeron/connect ctx)
          pub (.addPublication conn channel heartbeat-stream-id)
          initial-heartbeat (System/nanoTime)]
      (StatusPublisher. peer-config peer-id dst-peer-id site conn pub 
                        blocked completed nil nil initial-heartbeat)))
  (stop [this]
    (info "Closing status pub" (status-pub/info this))
    (.close conn)
    (try
     (when pub (.close pub))
     (catch io.aeron.exceptions.RegistrationException re
       (info "Error closing publication from status publisher" re)))
    (StatusPublisher. peer-config peer-id dst-peer-id site nil nil nil false false nil nil))
  (info [this]
    (let [dst-channel (autil/channel (:address site) (:port site))] 
      {:type :status-publisher
       :src-peer-id peer-id
       :dst-peer-id dst-peer-id
       :status-session-id session-id
       :short-id short-id
       :site site
       :dst-channel dst-channel
       :dst-session-id (.sessionId pub) 
       :stream-id (.streamId pub)
       :blocked? blocked
       :completed? completed
       :pos (.position pub)}))
  (get-session-id [this]
    session-id)
  (set-session-id! [this sess-id]
    (assert (or (nil? session-id) (= session-id sess-id)))
    (set! session-id sess-id)
    this)
  (set-short-id! [this short-id*]
    (set! short-id short-id*)
    this)
  (get-short-id [this] short-id)
  (set-heartbeat! [this]
    (set! heartbeat (System/nanoTime))
    this)
  (get-heartbeat [this]
    heartbeat)
  (block! [this]
    (assert (false? blocked))
    (set! blocked true)
    this)
  (unblock! [this]
    (set! blocked false))
  (blocked? [this]
    blocked)
  (set-completed! [this completed?]
    (set! completed completed?))
  (completed? [this]
    completed)
  (new-replica-version! [this]
    (set! blocked false)
    (set! completed false)
    this)
  (offer-barrier-status! [this replica-version epoch opts]
    (if session-id 
      (let [barrier-aligned (merge (t/heartbeat replica-version epoch peer-id
                                                dst-peer-id session-id short-id) 
                                   opts)
            buf (sz/serialize barrier-aligned)
            ret (.offer ^Publication pub buf 0 (.capacity buf))]
        (debug "Offered barrier status message:" 
               [ret barrier-aligned :session-id (.sessionId pub) :dst-site site])
        ret) 
      UNALIGNED_SUBSCRIBER))
  (offer-ready-reply! [this replica-version epoch]
    (let [ready-reply (t/ready-reply replica-version peer-id dst-peer-id session-id short-id) 
          buf (sz/serialize ready-reply)
          ret (.offer ^Publication pub buf 0 (.capacity buf))] 
      (debug "Offer ready reply!:" [ret ready-reply :session-id (.sessionId pub) :dst-site site])
      ret)))

(defn new-status-publisher [peer-config peer-id src-peer-id site]
  (->StatusPublisher peer-config peer-id src-peer-id site nil nil nil false false nil nil))
