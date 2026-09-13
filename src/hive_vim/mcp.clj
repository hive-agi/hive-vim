(ns hive-vim.mcp
  "MCP response shapes shared by the `vim` tool and the :vim editor port.
   Same shape hive-emacs returns, so a host treats both editors alike."
  (:require [clojure.data.json :as json]
            [hive-dsl.result :as r]
            [hive-vim.protocol.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def McpResponse
  [:map
   [:type [:= "text"]]
   [:text :string]
   [:isError {:optional true} :boolean]])

(defn text
  [value]
  {:type "text" :text (if (string? value) value (pr-str value))})

(defn json-text
  [value]
  {:type "text" :text (json/write-str value :escape-slash false)})

(defn error
  [message]
  {:type "text" :text (str message) :isError true})

(defn result->response
  "A Result as an MCP response: ok values as JSON, errors as
   \"[category] message\" with isError."
  [result]
  (if (r/ok? result)
    (json-text (:ok result))
    (error (str "[" (name (:error result)) "] " (:message result)))))

(defn param
  "Read param K from PARAMS whether the host kept string or keyword keys."
  [params k]
  (or (get params (keyword k)) (get params (name k))))

(defn invoke-opts
  "Session and timeout options carried in tool params."
  [params]
  (let [timeout (param params "timeout_ms")]
    (cond-> {}
      (string? (param params "session")) (assoc :session (param params "session"))
      (int? timeout) (assoc :timeout-ms timeout))))

(m/=> text [:=> [:cat :any] McpResponse])
(m/=> json-text [:=> [:cat :any] McpResponse])
(m/=> error [:=> [:cat :any] McpResponse])
(m/=> result->response [:=> [:cat s/Result] McpResponse])
(m/=> invoke-opts [:=> [:cat [:maybe map?]] [:map [:session {:optional true} :string]
                                              [:timeout-ms {:optional true} :int]]])
