(ns hive-vim.addon
  "Canonical hive-addon boundary for hive-vim (manifest id hive.vim): a
   hive-vessel vessel IAddon.

   Construction is pure. initialize! starts the HVCP transport, registers the
   :vim editor port, builds the :vim terminal backend and the vessel registry:
   hive-vessel's standard translators plus the :vessel/translators hook of every
   dependency the mount hands over under :mount/dependencies.

   What other addons use is contributed through hooks (see `hook-keys`);
   nothing here names a host or a sibling addon. shutdown! reverses all of it."
  (:require [clojure.java.io :as io]
            [hive-addon.protocol :as addon]
            [hive-vessel.core :as v]
            [hive-vim.editor.port :as editor-port]
            [hive-vim.protocol.schema :as s]
            [hive-vim.terminal :as terminal]
            [hive-vim.tools.vim :as vim-tool]
            [hive-vim.transport :as transport]
            [hive-vim.vessel :as vessel]
            [taoensso.timbre :as log]
            [hive-spi.vessel :as render-port]
            [hive-vessel.renderer :as renderer]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def addon-id-value "hive.vim")

(def default-port-file
  (str (io/file (System/getProperty "user.home") ".cache" "hive" "vim.port")))

(defn- flatten-config
  [seed runtime-config]
  (merge (:addon/config seed) seed (:addon/config runtime-config) runtime-config))

(defn- dependency-hooks
  "The hooks maps of the addons the mount injected under :mount/dependencies."
  [config]
  (keep (fn [[_ dep]]
          (when (addon/addon? dep)
            (try (addon/hooks dep) (catch Throwable _ nil))))
        (:mount/dependencies config)))

(defn- emit-fn
  "Transport emit fn: every event goes to :vim/emit-fn from config and to each
   listener registered through the :vim/register-listener! hook. A listener
   that throws is logged and skipped; the others still receive the event."
  [config listeners]
  (let [sinks (fn [] (cond->> (vals @listeners)
                       (:vim/emit-fn config) (cons (:vim/emit-fn config))))]
    (fn [event payload]
      (doseq [sink (sinks)]
        (try (sink event payload)
             (catch Exception e
               (log/debug "hive-vim event listener failed" {:event event :error (ex-message e)})))))))

(defn- initialize-addon!
  [state seed runtime-config]
  (locking state
    (if (= :active (:lifecycle @state))
      {:success? true :already-initialized? true}
      (let [config (flatten-config seed runtime-config)
            listeners (atom {})]
        (try
          (let [server (transport/start!
                        {:port (:vim/port config 0)
                         :port-file (:vim/port-file config default-port-file)
                         :emit-fn (emit-fn config listeners)
                         :hello-timeout-ms (:vim/hello-timeout-ms config s/hello-timeout-ms)})
                server-fn #(:server @state)
                _ (swap! state assoc :server server)
                port (editor-port/register! server-fn)
                term (terminal/vim-terminal server-fn terminal/terminal-key
                                            {:command (:vim/terminal-command config)})
                registry (atom (v/registry-from-hooks (dependency-hooks config)))
                metadata {:port (transport/port server)
                          :port-file (:port-file server)
                          :protocol s/protocol-version
                          :editor-port editor-port/port-key
                          :terminal terminal/terminal-key
                          :translators (count (:registry/translators @registry))}]
            (swap! state merge {:lifecycle :active
                                :metadata metadata
                                :editor-port port
                                :terminal term
                                :registry registry
                                :target (vessel/vessel-target server)
                                :listeners listeners
                                :vessel (vessel/create-vim-vessel #(:editor-port @state)
                                                                  (constantly term))})
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
      (some-> (:listeners @state) (reset! {}))
      (some-> (:terminal @state) addon/shutdown!)
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

(def hook-keys
  "Hooks an active hive.vim contributes. Each value is a fn.

   :vessel/target                (fn [])            hive-vessel target for Vim
   :vessel/instance              (fn [])            hive-addon IVessel (:editor :terminal)
   :vessel/dispatch!             (fn [op-or-ops])   plan and run ops on Vim
   :vessel/register-translators! (fn [translators]) extend this vessel's registry
   :vim/editor-port              (fn [])            hive-spi editor port
   :vim/terminal                 (fn [])            ITerminalAddon running lings in Vim
   :vim/register-listener!       (fn [id f])        receive (f event payload) for
                                                    :vim/connected :vim/event :vim/disconnected
   :vim/unregister-listener!     (fn [id])"
  #{:vessel/target :vessel/instance :vessel/dispatch! :vessel/register-translators!
    :vim/editor-port :vim/terminal :vim/register-listener! :vim/unregister-listener!})

(defn- addon-hooks
  [state]
  (let [{:keys [lifecycle registry target terminal editor-port listeners]
         descriptor :vessel} @state]
    (if (= :active lifecycle)
      {:vessel/target (fn [] target)
       :vessel/instance (fn [] (vessel/->ivessel descriptor))
       :vessel/dispatch! (fn [op-or-ops] (v/dispatch! registry target op-or-ops))
       :vessel/register-translators! (fn [translators]
                                       (swap! registry v/register-all translators)
                                       nil)
       :vim/editor-port (fn [] editor-port)
       :vim/terminal (fn [] terminal)
       :vim/register-listener! (fn [id f] (swap! listeners assoc id f) true)
       :vim/unregister-listener! (fn [id] (swap! listeners dissoc id) nil)}
      {})))

(defrecord HiveVimAddon [state seed]
  render-port/IRenderer
  (renderer-id [_] addon-id-value)
  (render! [_ ops] (renderer/deliver! (:target @state) ops))
  addon/IAddon
  (addon-id [_] addon-id-value)
  (addon-type [_] :native)
  (capabilities [_] #{:tools :health-reporting :editor :vessel :terminal})
  (initialize! [this runtime-config]
    (let [result (initialize-addon! state seed runtime-config)]
      (when (:success? result) (renderer/register! this))
      result))
  (shutdown! [this]
    (renderer/unregister! this)
    (shutdown-addon! state))
  (tools [_]
    (if (= :active (:lifecycle @state))
      (vim-tool/tools #(:server @state))
      []))
  (schema-extensions [_] [])
  (health [_] (addon-health state))
  (excluded-tools [_] #{})
  (hooks [_] (addon-hooks state)))

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

(defn terminal
  "The VimTerminal of an initialized ADDON, or nil."
  [addon]
  (:terminal @(:state addon)))
