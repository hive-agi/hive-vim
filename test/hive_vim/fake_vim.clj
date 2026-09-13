(ns hive-vim.fake-vim
  "A fake Vim for tests: connects to a hive-vim transport, says hello, and
   answers hive#rpc#dispatch calls from a handler map."
  (:require [clojure.data.json :as json]
            [clojure.walk :as walk]
            [hive-vim.protocol.verbs :as verbs]
            [hive-vim.transport :as t]
            [malli.generator :as mg])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.net Socket]
           [java.nio.charset StandardCharsets]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def hello
  {:protocol [1 0] :client "vim" :vim_version 901 :pid 42 :cwd "/tmp"
   :verbs (verbs/verb-names)})

(defn sample-result
  "A deterministic value conforming to VERB's result schema, as it looks after
   a JSON round trip (what the transport actually delivers)."
  [verb]
  (-> (mg/generate (:returns (verbs/spec-for verb)) {:seed 7 :size 2})
      (->> (walk/postwalk #(if (or (char? %) (symbol? %) (uuid? %)) (str %) %)))
      json/write-str
      (json/read-str :key-fn keyword)))

(defn default-handler
  "Answers every verb with its sample result."
  [verb _params]
  (if (verbs/spec-for verb)
    {:ok (sample-result verb)}
    {:err {:category "unknown-verb" :message verb}}))

(defn- send-frame
  [^BufferedWriter w frame]
  (locking w
    (.write w ^String (json/write-str frame))
    (.write w "\n")
    (.flush w)))

(defn start!
  "Connect to SERVER and serve dispatch calls with HANDLER (fn [verb params] ->
   envelope). Every call is recorded in :calls. Returns the fake."
  ([server] (start! server default-handler))
  ([server handler]
   (let [socket (Socket. "127.0.0.1" (int (t/port server)))
         reader (BufferedReader. (InputStreamReader. (.getInputStream socket)
                                                     StandardCharsets/UTF_8))
         writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream socket)
                                                      StandardCharsets/UTF_8))
         calls (atom [])]
     (send-frame writer [1 ["hello" hello]])
     (let [[_ reply] (t/read-frame reader)]
       {:socket socket
        :writer writer
        :calls calls
        :session (:session reply)
        :loop (future
                (loop []
                  (let [frame (t/read-frame reader)]
                    (when (vector? frame)
                      (let [[kind _fn [verb params] id] frame]
                        (when (= "call" kind)
                          (swap! calls conj [verb params])
                          (send-frame writer
                                      [id (try
                                            (handler verb params)
                                            (catch Exception e
                                              {:err {:category "vim-error"
                                                     :message (ex-message e)}}))])))
                      (recur)))))}))))

(defn event!
  "Send an event request from the fake Vim."
  [fake id event]
  (send-frame (:writer fake) [id ["event" event]]))

(defn stop!
  [fake]
  (.close ^Socket (:socket fake)))
