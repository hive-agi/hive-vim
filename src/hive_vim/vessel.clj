(ns hive-vim.vessel
  "The Vim vessel: a neutral descriptor (the hive-emacs.vessel shape) and an
   IVessel reification of it."
  (:require [hive-addon.vessel :as vessel]
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
