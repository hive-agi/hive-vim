(ns hive-vim.transport
  "HVCP v1 transport boundary: a loopback TCP server that Vim sessions connect
   to, with request/response correlation, handshake, events and a port file.

   Effectful. Framing and decisions come from hive-vim.protocol.codec."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hive-dsl.result :as r]
            [hive-vim.protocol.codec :as codec]
            [hive-vim.protocol.schema :as s]
            [malli.core :as m]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader BufferedWriter Closeable InputStreamReader
            OutputStreamWriter]
           [java.net InetAddress ServerSocket Socket SocketException
            SocketTimeoutException]
           [java.nio.charset StandardCharsets]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; Framing over a socket
;;; ============================================================================

(defn read-frame
  "Read the next frame from READER: one JSON value per line, as Vim's channel
   writes them. Returns ::eof at end of stream; an unparsable line comes back
   as {::unparsable line}, which the codec classifies as invalid."
  [^BufferedReader reader]
  (loop []
    (if-let [line (.readLine reader)]
      (if (str/blank? line)
        (recur)
        (try
          (json/read-str line :key-fn keyword)
          (catch Exception _
            {::unparsable line})))
      ::eof)))

(defn- write-frame!
  [session frame]
  (locking (:write-lock session)
    (let [^BufferedWriter w (:writer session)]
      (.write w ^String (json/write-str frame :escape-slash false))
      (.write w "\n")
      (.flush w))))

(defn- close-quietly!
  [^Closeable c]
  (try (.close c) (catch Exception _ nil)))

;;; ============================================================================
;;; Sessions
;;; ============================================================================

(defn- now
  [server]
  ((:clock-fn server)))

(defn- emit!
  [server event payload]
  (when-let [f (:emit-fn server)]
    (try
      (f event payload)
      (catch Exception e
        (log/debug "hive-vim emit-fn failed" {:event event :error (ex-message e)})))))

(defn- session-info
  [session]
  {:session (:id session)
   :hello (:hello session)
   :connected-at (:connected-at session)
   :last-active @(:last-active session)})

(defn sessions
  "Connected sessions, most recently active first."
  [server]
  (->> (vals @(:sessions server))
       (map session-info)
       (sort-by (juxt (comp - :last-active) :session))
       vec))

(defn select-session
  "The session SELECTOR names, or the most recently active one when SELECTOR is
   nil or blank. Returns a Result holding the session id."
  [server selector]
  (let [all @(:sessions server)]
    (cond
      (and (string? selector) (seq selector))
      (if (contains? all selector)
        (r/ok selector)
        (codec/error "no-session"
                     (str "no Vim session " (pr-str selector) ", connected: "
                          (vec (sort (keys all))))))

      (seq all)
      (r/ok (:session (first (sessions server))))

      :else
      (codec/error "no-session" "no Vim session is connected"))))

(defn- touch!
  [server session]
  (reset! (:last-active session) (now server)))

(defn- drop-session!
  [server session]
  (when (= session (get @(:sessions server) (:id session)))
    (swap! (:sessions server) dissoc (:id session))
    (emit! server :vim/disconnected {:session (:id session)}))
  (doseq [[_ p] @(:pending session)]
    (deliver p ::disconnected))
  (reset! (:pending session) {})
  (close-quietly! (:socket session)))

;;; ============================================================================
;;; Requests
;;; ============================================================================

(defn- clamp-timeout
  [timeout-ms]
  (-> (or timeout-ms s/default-timeout-ms)
      (max 1)
      (min s/max-timeout-ms)))

(defn request!
  "Send the op built by (OP-FN id) to session SESSION-ID and wait for Vim's
   reply. Returns (ok raw-result) or a :vim/timeout, :vim/disconnected or
   :vim/no-session error."
  [server session-id op-fn timeout-ms]
  (if-let [session (get @(:sessions server) session-id)]
    (let [[_ id] (swap-vals! (:last-id session) codec/next-id)
          reply (promise)
          timeout-ms (clamp-timeout timeout-ms)]
      (swap! (:pending session) assoc id reply)
      (try
        (write-frame! session (codec/encode-op (op-fn id)))
        (let [value (deref reply timeout-ms ::timeout)]
          (case value
            ::timeout (codec/error "timeout"
                                   (str "Vim did not answer within " timeout-ms " ms"))
            ::disconnected (codec/error "disconnected"
                                        (str "session " session-id " disconnected"))
            (r/ok value)))
        (catch SocketException e
          (drop-session! server session)
          (codec/error "disconnected" (ex-message e)))
        (finally
          (swap! (:pending session) dissoc id))))
    (codec/error "no-session" (str "no Vim session " (pr-str session-id)))))

(defn send!
  "Send a fire-and-forget op (no id) to SESSION-ID. Returns a Result."
  [server session-id op]
  (if-let [session (get @(:sessions server) session-id)]
    (try
      (write-frame! session (codec/encode-op (dissoc op :id)))
      (r/ok true)
      (catch SocketException e
        (drop-session! server session)
        (codec/error "disconnected" (ex-message e))))
    (codec/error "no-session" (str "no Vim session " (pr-str session-id)))))

;;; ============================================================================
;;; Connection handling
;;; ============================================================================

(defn- reply-to-request!
  [server session {:keys [id method params]}]
  (case method
    "event"
    (do
      (touch! server session)
      (emit! server :vim/event {:session (:id session) :event params})
      (write-frame! session (codec/reply-frame id "ok")))

    (write-frame! session
                  (codec/reply-frame id {:err {:category "unknown-verb"
                                               :message (str "hive does not handle "
                                                             method)}}))))

(defn- serve-session!
  [server session ^BufferedReader reader]
  (loop []
    (let [frame (read-frame reader)]
      (when-not (= ::eof frame)
        (let [msg (codec/classify frame)]
          (case (:kind msg)
            :response (when-let [p (get @(:pending session) (:id msg))]
                        (deliver p (:result msg)))
            :request (reply-to-request! server session msg)
            :invalid (log/warn "hive-vim ignored an invalid frame"
                               {:session (:id session) :reason (:reason msg)})))
        (recur)))))

(defn- handshake
  "Read the hello and decide. Returns {:reply HelloReply :hello Hello :id n} or
   {:reply rejection} when the first frame is not an acceptable hello."
  [server ^BufferedReader reader]
  (let [msg (codec/classify (read-frame reader))
        session-id (str "vim-" (swap! (:counter server) inc))]
    (cond
      (not (and (= :request (:kind msg)) (= "hello" (:method msg))))
      {:reply {:accepted false :reason "first message must be hello"}}

      (not (m/validate s/Hello (:params msg)))
      {:id (:id msg)
       :reply {:accepted false
               :reason (str "invalid hello: " (s/explain-str s/Hello (:params msg)))}}

      :else
      {:id (:id msg)
       :hello (:params msg)
       :reply (codec/hello-reply (:params msg) session-id)})))

(defn- handle-connection!
  [server ^Socket socket]
  (let [reader (BufferedReader. (InputStreamReader. (.getInputStream socket)
                                                    StandardCharsets/UTF_8))
        writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream socket)
                                                     StandardCharsets/UTF_8))
        session {:socket socket
                 :writer writer
                 :write-lock (Object.)
                 :pending (atom {})
                 :last-id (atom 0)}]
    (try
      (.setSoTimeout socket (int (:hello-timeout-ms server)))
      (let [{:keys [id hello reply]} (handshake server reader)]
        (when id
          (write-frame! session (codec/reply-frame id reply)))
        (if (:accepted reply)
          (let [session (assoc session
                               :id (:session reply)
                               :hello hello
                               :connected-at (now server)
                               :last-active (atom (now server)))]
            (.setSoTimeout socket 0)
            (swap! (:sessions server) assoc (:id session) session)
            (emit! server :vim/connected {:session (:id session) :hello hello})
            (log/info "Vim session connected" {:session (:id session) :pid (:pid hello)})
            (try
              (serve-session! server session reader)
              (finally
                (drop-session! server session))))
          (do
            (log/info "Vim connection rejected" {:reason (:reason reply)})
            (close-quietly! socket))))
      (catch SocketTimeoutException _
        (log/info "Vim connection closed: no hello in time")
        (close-quietly! socket))
      (catch Exception e
        (log/debug "Vim connection ended" {:error (ex-message e)})
        (close-quietly! socket)))))

(defn- daemon-thread!
  [name f]
  (doto (Thread. ^Runnable f ^String name)
    (.setDaemon true)
    (.start)))

(defn- accept-loop!
  [server]
  (let [^ServerSocket ss (:server-socket server)]
    (loop []
      (when @(:running? server)
        (when-let [socket (try (.accept ss) (catch SocketException _ nil))]
          (daemon-thread! "hive-vim-session" #(handle-connection! server socket)))
        (recur)))))

;;; ============================================================================
;;; Lifecycle
;;; ============================================================================

(defn- write-port-file!
  [path port]
  (when path
    (let [f (io/file path)]
      (io/make-parents f)
      (spit f (str port "\n")))))

(defn port
  "The TCP port SERVER listens on."
  [server]
  (.getLocalPort ^ServerSocket (:server-socket server)))

(defn start!
  "Listen on loopback and accept Vim sessions.

   opts: :port (0 picks a free port), :port-file (written with the bound port),
         :emit-fn (fn [event payload]) for :vim/connected :vim/event
         :vim/disconnected, :hello-timeout-ms, :clock-fn."
  [{:keys [port port-file emit-fn hello-timeout-ms clock-fn]
    :or {port 0 hello-timeout-ms s/hello-timeout-ms}}]
  (let [ss (ServerSocket. (int port) 50 (InetAddress/getLoopbackAddress))
        server {:server-socket ss
                :sessions (atom {})
                :counter (atom 0)
                :running? (atom true)
                :port-file port-file
                :emit-fn emit-fn
                :hello-timeout-ms hello-timeout-ms
                :clock-fn (or clock-fn #(System/currentTimeMillis))}]
    (write-port-file! port-file (.getLocalPort ss))
    (daemon-thread! "hive-vim-accept" #(accept-loop! server))
    (log/info "hive-vim listening" {:port (.getLocalPort ss) :port-file port-file})
    server))

(defn stop!
  "Stop accepting, disconnect every session and remove the port file."
  [server]
  (when (compare-and-set! (:running? server) true false)
    (close-quietly! (:server-socket server))
    (doseq [session (vals @(:sessions server))]
      (drop-session! server session))
    (when-let [path (:port-file server)]
      (let [f (io/file path)]
        (when (and (.exists f) (= (str (port server)) (.trim ^String (slurp f))))
          (.delete f))))
    (log/info "hive-vim stopped"))
  nil)

(defn running?
  [server]
  (boolean (and server @(:running? server))))
