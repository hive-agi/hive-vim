(ns hive-vim.tools.vim
  "The consolidated `vim` MCP tool, projected from the verb catalogue."
  (:require [clojure.string :as str]
            [hive-vim.client :as client]
            [hive-vim.mcp :as mcp]
            [hive-vim.protocol.verbs :as verbs]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def extra-commands ["sessions" "help"])

(def command-properties
  {"session" {:type "string"
              :description "Target Vim session id (see sessions). Default: the most recently active session"}
   "timeout_ms" {:type "integer"
                 :description "Timeout in milliseconds (default 5000, max 30000)"}})

(defn help-text
  []
  (str "Vim operations over HVCP v1. Commands:\n"
       "  - sessions: connected Vim sessions, most recently active first\n"
       "  - help: this text\n"
       (str/join "\n" (map #(str "  - " (:verb %) ": " (:doc %)) verbs/catalogue))))

(defn make-handler
  "The tool handler for the transport SERVER-FN returns."
  [server-fn]
  (fn [params]
    (let [command (some-> (mcp/param params "command") str str/trim)]
      (cond
        (= "help" command) (mcp/text (help-text))
        (= "sessions" command) (mcp/json-text (client/sessions (server-fn)))
        (verbs/spec-for command)
        (mcp/result->response
         (client/invoke! (server-fn) (mcp/invoke-opts params) command params))
        :else
        (mcp/error (str "[unknown-verb] unknown command " (pr-str command)
                        ". Valid: " (str/join ", " (into extra-commands (verbs/verb-names)))))))))

(defn tool-def
  [server-fn]
  {:name "vim"
   :consolidated true
   :description (str "Vim operations over HVCP v1: "
                     (str/join ", " (verbs/verb-names))
                     ", sessions (connected Vims). Use command='help' to list all.")
   :inputSchema (verbs/mcp-input-schema extra-commands command-properties)
   :handler (make-handler server-fn)})

(defn tools
  [server-fn]
  [(tool-def server-fn)])
