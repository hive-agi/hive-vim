(ns hive-vim.transport-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-dsl.result :as r]
            [hive-vim.protocol.codec :as codec]
            [hive-vim.transport :as t])
  (:import [java.io BufferedReader BufferedWriter File InputStreamReader OutputStreamWriter]
           [java.net Socket]
           [java.nio.charset StandardCharsets]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; A fake Vim client speaking the JSON channel protocol
;;; ============================================================================

(defn- connect
  [server]
  (let [socket (Socket. "127.0.0.1" (int (t/port server)))]
    {:socket socket
     :reader (BufferedReader. (InputStreamReader. (.getInputStream socket)
                                                  StandardCharsets/UTF_8))
     :writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream socket)
                                                   StandardCharsets/UTF_8))
     :next-id (atom 0)}))

(defn- send-frame
  [{:keys [^BufferedWriter writer]} frame]
  (.write writer ^String (json/write-str frame))
  (.write writer "\n")
  (.flush writer))

(defn- recv-frame
  [{:keys [reader]}]
  (let [frame (t/read-frame reader)]
    (if (= :hive-vim.transport/eof frame) ::eof frame)))

(defn- vim-request
  "Send [n [method params]] and return the reply payload."
  [client method params]
  (let [id (swap! (:next-id client) inc)]
    (send-frame client [id [method params]])
    (let [[reply-id payload] (recv-frame client)]
      (assert (= id reply-id))
      payload)))

(def ^:private hello
  {:protocol [1 0] :client "vim" :vim_version 901 :pid 42 :cwd "/tmp"
   :verbs ["status"]})

(defn- connect-hello
  ([server] (connect-hello server hello))
  ([server hello-map]
   (let [client (connect server)]
     (assoc client :reply (vim-request client "hello" hello-map)))))

(defn- serve-dispatch
  "Answer N incoming call frames with f applied to [verb params]."
  [client n f]
  (future
    (dotimes [_ n]
      (let [[_call _fn [verb params] id] (recv-frame client)]
        (send-frame client [id (f verb params)])))))

(defn- wait-until
  [pred]
  (loop [n 0]
    (cond
      (pred) true
      (> n 200) false
      :else (do (Thread/sleep 10) (recur (inc n))))))

(defn- close
  [{:keys [^Socket socket]}]
  (.close socket))

;;; ============================================================================
;;; Fixture
;;; ============================================================================

(def ^:dynamic *server* nil)
(def ^:dynamic *events* nil)
(def ^:dynamic *port-file* nil)

(use-fixtures :each
  (fn [f]
    (let [events (atom [])
          port-file (File/createTempFile "hive-vim" ".port")
          server (t/start! {:port-file (str port-file)
                            :emit-fn (fn [event payload] (swap! events conj [event payload]))
                            :hello-timeout-ms 300})]
      (try
        (binding [*server* server *events* events *port-file* port-file]
          (f))
        (finally
          (t/stop! server)
          (.delete port-file))))))

;;; ============================================================================
;;; Tests
;;; ============================================================================

(deftest port-file-names-the-bound-port
  (is (pos? (t/port *server*)))
  (is (= (str (t/port *server*)) (.trim ^String (slurp *port-file*)))))

(deftest handshake-accepts-matching-major
  (let [client (connect-hello *server*)]
    (is (= {:accepted true :session "vim-1" :protocol [1 0]} (:reply client)))
    (is (wait-until #(= 1 (count (t/sessions *server*)))))
    (is (= hello (:hello (first (t/sessions *server*)))))
    (is (= :vim/connected (ffirst @*events*)))
    (close client)))

(deftest handshake-rejects-other-major
  (let [client (connect-hello *server* (assoc hello :protocol [2 0]))]
    (is (false? (:accepted (:reply client))))
    (is (= ::eof (recv-frame client)) "rejected connections are closed")
    (is (empty? (t/sessions *server*)))))

(deftest handshake-rejects-malformed-hello
  (let [client (connect-hello *server* {:protocol [1 0]})]
    (is (false? (:accepted (:reply client))))
    (is (re-find #"invalid hello" (:reason (:reply client))))))

(deftest silent-connections-are-closed
  (let [client (connect *server*)]
    (is (= ::eof (recv-frame client)))
    (is (empty? (t/sessions *server*)))))

(deftest request-round-trip
  (let [client (connect-hello *server*)
        session (:session (:reply client))
        answered (serve-dispatch client 1 (fn [verb params] {:ok [verb params]}))]
    (is (= (r/ok {:ok ["buffers" {:all true}]})
           (t/request! *server* session #(codec/dispatch-op "buffers" {:all true} %) 1000)))
    @answered
    (close client)))

(deftest concurrent-requests-are-correlated
  (let [client (connect-hello *server*)
        session (:session (:reply client))
        n 20
        frames (future (doall (repeatedly n #(recv-frame client))))
        results (mapv (fn [i]
                        (future (t/request! *server* session
                                            #(codec/dispatch-op "eval" {:code (str i)} %)
                                            2000)))
                      (range n))]
    (doseq [[_call _ [_ {:keys [code]}] id] (reverse @frames)]
      (send-frame client [id {:ok code}]))
    (is (= (mapv #(r/ok {:ok (str %)}) (range n)) (mapv deref results)))
    (close client)))

(deftest unanswered-requests-time-out
  (let [client (connect-hello *server*)
        session (:session (:reply client))
        started (System/currentTimeMillis)
        result (t/request! *server* session #(codec/dispatch-op "status" {} %) 150)]
    (is (= :vim/timeout (:error result)))
    (is (< (- (System/currentTimeMillis) started) 1000))
    (close client)))

(deftest disconnect-fails-pending-requests
  (let [client (connect-hello *server*)
        session (:session (:reply client))
        pending (future (t/request! *server* session #(codec/dispatch-op "status" {} %) 5000))]
    (recv-frame client)
    (close client)
    (is (= :vim/disconnected (:error @pending)))
    (is (wait-until #(empty? (t/sessions *server*))))
    (is (some #(= :vim/disconnected (first %)) @*events*))))

(deftest events-update-activity-and-default-session
  (let [a (connect-hello *server*)
        b (connect-hello *server*)]
    (is (wait-until #(= 2 (count (t/sessions *server*)))))
    (testing "an event is acknowledged and forwarded"
      (Thread/sleep 5)
      (is (= "ok" (vim-request a "event" {:type "focus"})))
      (is (some #(= [:vim/event {:session "vim-1" :event {:type "focus"}}] %) @*events*)))
    (testing "the most recently active session is the default target"
      (is (= (r/ok "vim-1") (t/select-session *server* nil))))
    (testing "explicit selection and unknown sessions"
      (is (= (r/ok "vim-2") (t/select-session *server* "vim-2")))
      (is (= :vim/no-session (:error (t/select-session *server* "vim-9")))))
    (close a)
    (close b)))

(deftest unknown-vim-methods-get-an-err-envelope
  (let [client (connect-hello *server*)]
    (is (= "unknown-verb" (get-in (vim-request client "bogus" {}) [:err :category])))
    (close client)))

(deftest no-session-errors
  (is (= :vim/no-session (:error (t/select-session *server* nil))))
  (is (= :vim/no-session (:error (t/request! *server* "vim-1" #(codec/dispatch-op "status" {} %) 100)))))

(deftest stop-closes-sessions-and-removes-port-file
  (let [client (connect-hello *server*)]
    (is (wait-until #(= 1 (count (t/sessions *server*)))))
    (t/stop! *server*)
    (is (= ::eof (recv-frame client)))
    (is (not (.exists (io/file *port-file*))))
    (is (not (t/running? *server*)))))
