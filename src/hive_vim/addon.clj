(ns hive-vim.addon
  "Canonical hive-addon boundary for hive-vim (manifest id hive.vim).

   Construction is pure. initialize! starts the HVCP transport and registers
   the :vim editor port; shutdown! reverses both."
  (:require [clojure.java.io :as io]
            [hive-addon.protocol :as addon]
            [hive-vim.editor.port :as editor-port]
            [hive-vim.protocol.schema :as s]
            [hive-vim.tools.vim :as vim-tool]
            [hive-vim.transport :as transport]
            [hive-vim.vessel :as vessel]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def addon-id-value "hive.vim")

(def default-port-file
  (str (io/file (System/getProperty "user.home") ".cache" "hive" "vim.port")))

(defn- flatten-config
  [seed runtime-config]
  (merge (:addon/config seed) seed (:addon/config runtime-config) runtime-config))

(defn- initialize-addon!
  [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (let [config (flatten-config seed runtime-config)]
        (try
          (let [server (transport/start!
                        {:port (:vim/port config 0)
                         :port-file (:vim/port-file config default-port-file)
                         :emit-fn (:vim/emit-fn config)
                         :hello-timeout-ms (:vim/hello-timeout-ms config s/hello-timeout-ms)})
                server-fn #(:server @state)
                _ (swap! state assoc :server server)
                port (editor-port/register! server-fn)
                metadata {:port (transport/port server)
                          :port-file (:port-file server)
                          :protocol s/protocol-version
                          :editor-port editor-port/port-key}]
            (swap! state merge {:lifecycle :active
                                :metadata metadata
                                :editor-port port
                                :vessel (vessel/create-vim-vessel #(:editor-port @state))})
            (log/info "hive-vim initialized" metadata)
            {:success? true :errors [] :metadata metadata})
          (catch Exception e
            (some-> (:server @state) transport/stop!)
            (editor-port/unregister!)
            (reset! state {:lifecycle :error :errors [(ex-message e)]})
            (log/error "hive-vim initialization failed" {:error (ex-message e)})
            {:success? false :errors [(ex-message e)]}))))))

(defn- shutdown-addon!
  [state]
  (locking state
    (when (#{:active :error} (:lifecycle @state))
      (editor-port/unregister!)
      (some-> (:server @state) transport/stop!)
      (log/info "hive-vim shut down"))
    (reset! state {:lifecycle :stopped}))
  nil)

(defn- addon-health
  [state]
  (let [{:keys [lifecycle metadata errors server]} @state]
    (if (and (= :active lifecycle) (transport/running? server))
      (let [n (count (transport/sessions server))]
        {:status (if (pos? n) :ok :degraded)
         :details (assoc metadata :sessions n)})
      {:status :down
       :details (cond-> {:lifecycle (or lifecycle :new)}
                  (seq errors) (assoc :errors errors))})))

(defrecord HiveVimAddon [state seed]
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting :editor :vessel})
  (initialize! [_ runtime-config] (initialize-addon! state seed runtime-config))
  (shutdown! [_] (shutdown-addon! state))
  (tools [_]
    (if (= :active (:lifecycle @state))
      (vim-tool/tools #(:server @state))
      []))
  (schema-extensions [_] [])
  (health [_] (addon-health state))
  (excluded-tools [_] #{})
  (hooks [_] {}))

(defn make-addon
  "An uninitialized IAddon. No socket, file or registry mutation."
  ([] (make-addon {}))
  ([seed] (->HiveVimAddon (atom {:lifecycle :new}) (or seed {}))))

(defn addon-ctor
  "Pure hive-addon.mount constructor: config -> uninitialized IAddon."
  [config]
  (make-addon config))

(defn vessel
  "The Vim vessel descriptor of an initialized ADDON, or nil."
  [addon]
  (:vessel @(:state addon)))

(defn server
  "The running transport of ADDON, or nil."
  [addon]
  (:server @(:state addon)))
