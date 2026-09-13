(ns hive-vim.terminal
  "VimTerminal: hive-addon ITerminalAddon over Vim's :terminal.

   Lings run in hidden terminal buffers named hive:<id> inside a connected Vim.
   Each ling is pinned to the session it was spawned in, so a second Vim never
   receives another's keystrokes. The terminal verbs of the catalogue do the
   work; this namespace only maps the protocol onto them."
  (:require [hive-addon.protocol :as addon]
            [hive-addon.terminal :as term]
            [hive-dsl.result :as r]
            [hive-vim.client :as client]
            [hive-vim.transport :as transport]
            [malli.core :as m]
            [hive-vim.protocol.codec :as codec]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def default-command
  "Argv a ling runs when neither the spawn opts nor the addon config name one."
  ["claude"])

(def terminal-key
  "The terminal-id of hive.vim's terminal, as offered through the :vim/terminal hook."
  :vim)

(defn terminal-name
  "The Vim buffer name of ling ID, the :terminal a :ui/send-to-terminal op targets."
  [id]
  (str "hive:" id))

(defn spawn-params
  "terminal-spawn params from a ling CTX, spawn OPTS and default COMMAND."
  [ctx opts command]
  (cond-> {:id (:id ctx)
           :cmd (vec (or (:command opts) command))}
    (:cwd ctx) (assoc :cwd (:cwd ctx))
    (seq (:env opts)) (assoc :env (into {} (map (fn [[k v]] [(name k) (str v)])) (:env opts)))))

(defn status->slave
  "ITerminalAddon status map for ling ID from a terminal-status Result."
  [id result]
  (when-let [{:keys [status]} (:ok result)]
    {:slave/id id :slave/status (keyword status)}))

(defn kill->result
  [id result]
  (if (r/ok? result)
    {:killed? true :id id}
    {:killed? false :id id :reason (keyword (name (:error result)))}))

(defn interrupt->result
  [id result]
  (if (r/ok? result)
    {:success? true :ling-id id}
    {:success? false :ling-id id :errors [(or (:message result) (str (:error result)))]}))

(defn- fail!
  [verb id result]
  (throw (ex-info (str "vim " verb " failed for " id ": " (:message result))
                  {:id id :ling-id id :error (:error result) :message (:message result)})))

(defn- invoke
  "Run VERB for ling ID on the session it is pinned to."
  [{:keys [server-fn contexts timeout-ms]} id verb params]
  (client/invoke! (server-fn)
                  {:session (get-in @contexts [id :session]) :timeout-ms timeout-ms}
                  verb (assoc params :id id)))

(defn context-of
  "Recorded {:session :cwd :project-id} of ling ID, or nil."
  [terminal id]
  (get @(:contexts terminal) id))

(defrecord VimTerminal [server-fn terminal-kw command session timeout-ms contexts]
  term/ITerminalAddon
  (terminal-id [_] terminal-kw)

  (terminal-spawn! [this ctx opts]
    (let [id (:id ctx)
          server (server-fn)
          result (r/let-ok [session-id (if (and server (transport/running? server))
                                         (transport/select-session server session)
                                         (codec/error "no-session" "hive-vim transport is not running"))
                            spawned (client/invoke! server {:session session-id :timeout-ms timeout-ms}
                                                    "terminal-spawn" (spawn-params ctx opts command))]
                   (r/ok (assoc spawned :session session-id)))]
      (when-not (r/ok? result) (fail! "terminal-spawn" id result))
      (swap! contexts assoc id (assoc (select-keys ctx [:cwd :project-id])
                                      :session (get-in result [:ok :session])))
      id))

  (terminal-dispatch! [this ctx task-opts]
    (let [id (:id ctx)
          result (invoke this id "terminal-dispatch" {:text (str (:task task-opts))})]
      (when-not (r/ok? result) (fail! "terminal-dispatch" id result))
      true))

  (terminal-status [this ctx _ds-status]
    (let [id (:id ctx)]
      (status->slave id (invoke this id "terminal-status" {}))))

  (terminal-kill! [this ctx]
    (let [id (:id ctx)
          result (kill->result id (invoke this id "terminal-kill" {}))]
      (when (:killed? result) (swap! contexts dissoc id))
      result))

  (terminal-interrupt! [this ctx]
    (let [id (:id ctx)]
      (interrupt->result id (invoke this id "terminal-interrupt" {}))))

  addon/IAddon
  (addon-id [_] (str "hive.vim.terminal." (name terminal-kw)))
  (addon-type [_] :native)
  (capabilities [_] #{:terminal})
  (initialize! [_ _config] {:success? true :errors []})
  (shutdown! [_] (reset! contexts {}) nil)
  (tools [_] [])
  (schema-extensions [_] [])
  (health [_] {:status :ok :details {:terminals (count @contexts)}})
  (excluded-tools [_] #{})
  (hooks [_] {}))

(defn read-lines
  "Visible lines of ling ID, or {:error kw} when Vim cannot answer."
  [terminal id]
  (let [result (invoke terminal id "terminal-read" {})]
    (if (r/ok? result)
      (:lines (:ok result))
      {:error (:error result)})))

(defn vim-terminal
  "ITerminalAddon TERMINAL-KW running lings in the Vim that SERVER-FN's
   transport reaches. OPTS: :command (argv, default `default-command`),
   :session (pin every spawn to one Vim), :timeout-ms."
  ([server-fn] (vim-terminal server-fn :vim {}))
  ([server-fn terminal-kw {:keys [command session timeout-ms]}]
   (->VimTerminal server-fn terminal-kw (vec (or command default-command))
                  session timeout-ms (atom {}))))

(def ^:private Ctx [:map [:id [:string {:min 1}]]])
(def ^:private Result [:or [:map [:ok :any]] [:map [:error :keyword]]])

(m/=> terminal-name [:=> [:cat :string] :string])
(m/=> spawn-params [:=> [:cat Ctx [:maybe :map] [:sequential :string]] :map])
(m/=> status->slave [:=> [:cat :string Result] [:maybe [:map [:slave/id :string] [:slave/status :keyword]]]])
(m/=> kill->result [:=> [:cat :string Result] [:map [:killed? :boolean] [:id :string]]])
(m/=> interrupt->result [:=> [:cat :string Result] [:map [:success? :boolean] [:ling-id :string]]])
