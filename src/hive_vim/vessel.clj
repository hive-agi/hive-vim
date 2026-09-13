(ns hive-vim.vessel
  "The Vim vessel: a neutral descriptor (the hive-emacs.vessel shape), an
   IVessel reification of it, and the hive-vessel target that executes
   :vim-channel ops over this addon's transport.

   No dependency on hive-vessel: a target is a plain map, so the contract is
   the shape. hive-vessel lowers an action to [\"call\" fn args] and calls
   :vessel/execute!; the payload travels over the same session-managed,
   reconnecting channel HVCP verbs use.

   An op batch is not a transaction: dispatch! stops at the first op whose
   execution throws and reports how many already ran."
  (:require [hive-addon.vessel :as vessel]
            [hive-dsl.result :as r]
            [hive-vim.client :as client]
            [taoensso.timbre :as log]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def capabilities #{:editor})

(defn create-vim-vessel
  "Descriptor for the Vim vessel. PORT-FN returns the :vim editor port or nil."
  [port-fn]
  {:vessel/id :vim
   :vessel/capabilities capabilities
   ;; Vim sessions are editors, not agent hosts: no agent runs inside one yet.
   :vessel/resolve-context (fn [_agent-id] nil)
   :vessel/addon (fn [capability]
                   (when (= :editor capability) (port-fn)))
   :vessel/initialize! (fn [config]
                         (log/info "Vim vessel initialized"
                                   (when config {:config-keys (keys config)}))
                         nil)
   :vessel/shutdown! (fn []
                       (log/info "Vim vessel shut down")
                       nil)})

(def dialect
  "The hive-vessel dialect this vessel speaks."
  :vim-channel)

(defn execute!
  "Run a lowered hive-vessel op on SERVER. Returns the value Vim answered.

   Throws ex-info on failure. hive-vessel's dispatch! reports that as
   {:error {:failure/reason :execute-threw}} carrying how many ops of the batch
   already ran: a loud dispatch failure, never a silent drop. Translator
   fallback is a separate mechanism and applies to TRANSLATION failures only,
   so a Vim that is down does not silently re-route to another lowering."
  [server opts {:native/keys [payload] :as op}]
  (when-not (= dialect (:native/dialect op))
    (throw (ex-info "the Vim vessel executes :vim-channel only"
                    {:dialect (:native/dialect op)})))
  (let [result (client/execute-native! server opts payload)]
    (if (r/ok? result)
      (:ok result)
      (throw (ex-info (str "vim vessel: " (:message result))
                      {:error (:error result) :payload payload})))))

(defn vessel-target
  "A hive-vessel target backed by SERVER: {:vessel/id :vim :vessel/dialect
   :vim-channel :vessel/execute! f}. OPTS may pin a :session or :timeout-ms,
   and :features advertises addon-specific capabilities to translator guards."
  ([server] (vessel-target server {}))
  ([server {:keys [features] :as opts}]
   (cond-> {:vessel/id :vim
            :vessel/dialect dialect
            :vessel/execute! (fn [op] (execute! server (dissoc opts :features) op))}
     features (assoc :vessel/features features))))

(defn ->ivessel
  "An IVessel over DESCRIPTOR."
  [descriptor]
  (reify vessel/IVessel
    (vessel-id [_] (:vessel/id descriptor))
    (capabilities [_] (:vessel/capabilities descriptor))
    (resolve-context [_ agent-id] ((:vessel/resolve-context descriptor) agent-id))
    (addon [_ capability] ((:vessel/addon descriptor) capability))
    (initialize! [_ config] ((:vessel/initialize! descriptor) config))
    (shutdown! [_] ((:vessel/shutdown! descriptor)))))
