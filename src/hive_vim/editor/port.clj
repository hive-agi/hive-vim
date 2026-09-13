(ns hive-vim.editor.port
  "hive-spi editor ports for Vim, registered under :vim.

   Substrate and buffer methods are generated from the verb catalogue, so a
   method exists here exactly when a verb backs it. The daemon surface is the
   set of connected Vim sessions."
  (:require [hive-spi.editor.ports :as ports]
            [hive-spi.editor.registry :as registry]
            [hive-vim.client :as client]
            [hive-vim.mcp :as mcp]
            [hive-vim.protocol.verbs :as verbs]
            [hive-vim.transport :as transport]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def port-key
  "Registry key. Never :default, which belongs to whichever editor the host
   treats as primary."
  :vim)

(defn- invoke-method
  "Run the verb backing SPI-METHOD for the port's current server."
  [{:keys [server-fn selected]} spi-method params]
  (let [{:keys [verb]} (verbs/spec-for-spi-method spi-method)
        opts (mcp/invoke-opts params)
        opts (cond-> opts
               (and (nil? (:session opts)) @selected) (assoc :session @selected))]
    (mcp/result->response (client/invoke! (server-fn) opts verb params))))

(defmacro ^:private defport
  "defrecord whose substrate and buffer methods all route through invoke-method."
  [name fields & daemon-impl]
  (letfn [(impl [protocol methods]
            (cons protocol
                  (for [m methods]
                    `(~(symbol (clojure.core/name m)) [this# params#]
                      (invoke-method this# ~m params#)))))]
    `(defrecord ~name ~fields
       ~@(impl `ports/IEditorPort
               (map :spi-method (filter #(= :substrate (:surface %)) verbs/catalogue)))
       ~@(impl `ports/IEditorBufferPort
               (map :spi-method (filter #(= :buffer (:surface %)) verbs/catalogue)))
       ~@daemon-impl)))

(defn- daemon-name
  [params]
  (let [n (mcp/param params "name")]
    (when (and (string? n) (seq n)) n)))

(defport VimEditorPort [server-fn selected]
  ports/IEditorDaemonPort
  (list-daemons [_ _params]
    (mcp/json-text (client/sessions (server-fn))))

  (select-daemon [_ params]
    (let [server (server-fn)
          n (daemon-name params)]
      (cond
        (nil? n) (mcp/error "[invalid-params] name is required")
        (not (and server (transport/running? server)))
        (mcp/error "[no-session] hive-vim transport is not running")
        :else
        (let [result (transport/select-session server n)]
          (when (:ok result) (reset! selected n))
          (mcp/result->response (if (:ok result) {:ok {:selected n}} result))))))

  (daemon-health [this params]
    (let [n (daemon-name params)
          targets (if n [n] (map :session (client/sessions (server-fn))))]
      (mcp/json-text
       (into {}
             (for [session targets]
               (let [started (System/currentTimeMillis)
                     result (client/invoke! (server-fn) {:session session :timeout-ms 2000}
                                            "status" {})]
                 [session (if (:ok result)
                            {:status "ok" :latency_ms (- (System/currentTimeMillis) started)}
                            {:status "down" :error (name (:error result))
                             :message (:message result)})]))))))

  (spawn-daemon [_ _params]
    (mcp/error "[unsupported] hive-vim does not start Vim; open Vim with the hive plugin on its runtimepath and it connects"))

  (kill-daemon [_ params]
    (if-let [n (daemon-name params)]
      (if (some #(= n (:session %)) (client/sessions (server-fn)))
        (mcp/error (str "[unsupported] hive-vim does not quit Vim sessions (" n ")"))
        (mcp/error (str "[not-found] no Vim session " (pr-str n))))
      (mcp/error "[invalid-params] name is required"))))

(defn ->port
  "A port reading the live transport from SERVER-FN (fn [] server-or-nil)."
  [server-fn]
  (->VimEditorPort server-fn (atom nil)))

(defn register!
  "Install a port under :vim. Returns the port."
  [server-fn]
  (registry/register-port! port-key (->port server-fn)))

(defn unregister!
  []
  (registry/unregister-port! port-key))
