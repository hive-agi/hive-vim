(ns hive-vim.protocol.verbs
  "HVCP v1 verb catalogue: the single definition of what hive can ask Vim to do.

   The MCP `vim` tool, the hive-spi editor port and the Vim-side conformance
   check are projections of `catalogue`. Add a verb here and in
   vim/autoload/hive/rpc.vim; the e2e test fails until both agree.

   Host-free cljc. Spec: docs/protocol.md (L3)."
  (:require [hive-dsl.result :as r]
            [hive-vim.protocol.codec :as codec]
            [hive-vim.protocol.schema :as s]
            [malli.core :as m]
            [malli.json-schema :as mjs]
            [malli.util :as mu]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; Result shapes
;;; ============================================================================

(def Buffer
  [:map
   [:nr :int]
   [:name :string]
   [:modified :boolean]
   [:listed :boolean]
   [:filetype :string]
   [:buftype :string]])

(def CurrentBuffer
  (m/form (mu/merge Buffer [:map [:line :int] [:col :int]])))

(def BufferInfo
  (m/form (mu/merge Buffer [:map [:line_count :int] [:windows [:vector :int]]])))

(def Status
  [:map
   [:vim_version :int]
   [:pid :int]
   [:cwd :string]
   [:mode :string]
   [:buffer Buffer]])

(def NoParams [:map])

;;; ============================================================================
;;; Catalogue
;;; ============================================================================

(def catalogue
  "Every HVCP v1 verb, in presentation order."
  [{:verb "eval" :surface :substrate :spi-method :editor-eval
    :doc "Evaluate Vimscript: an expression (mode expr, default) or an Ex command whose output is returned (mode ex)"
    :params [:map
             [:code [:string {:min 1}]]
             [:mode {:optional true} [:enum "expr" "ex"]]]
    :returns [:map [:value :any]]}
   {:verb "notify" :surface :substrate :spi-method :editor-notify
    :doc "Show a message to the user"
    :params [:map
             [:message [:string {:min 1}]]
             [:level {:optional true} [:enum "info" "warn" "error"]]]
    :returns [:map [:shown :boolean]]}
   {:verb "status" :surface :substrate :spi-method :editor-status
    :doc "Vim version, pid, cwd, mode and the focused buffer"
    :params NoParams
    :returns Status}
   {:verb "capabilities" :surface :substrate :spi-method :editor-capabilities
    :doc "Protocol version, implemented verbs and Vim feature flags"
    :params NoParams
    :returns [:map
              [:protocol s/ProtocolVersion]
              [:verbs [:vector :string]]
              [:features [:map-of :keyword :boolean]]]}
   {:verb "buffers" :surface :buffer :spi-method :list-buffers
    :doc "Every listed buffer"
    :params NoParams
    :returns [:vector Buffer]}
   {:verb "current" :surface :buffer :spi-method :current-buffer
    :doc "The focused buffer with cursor position"
    :params NoParams
    :returns CurrentBuffer}
   {:verb "buffer-info" :surface :buffer :spi-method :buffer-info
    :doc "Details for one buffer by name"
    :params [:map [:buffer_name [:string {:min 1}]]]
    :returns BufferInfo}
   {:verb "special-buffers" :surface :buffer :spi-method :special-buffers
    :doc "Buffers not backed by a file (terminal, help, quickfix, nofile)"
    :params NoParams
    :returns [:vector Buffer]}
   {:verb "switch" :surface :buffer :spi-method :switch-buffer
    :doc "Focus a buffer by name"
    :params [:map [:buffer [:string {:min 1}]]]
    :returns Buffer}
   {:verb "find" :surface :buffer :spi-method :find-file
    :doc "Open a file, creating its buffer if needed"
    :params [:map [:file [:string {:min 1}]]]
    :returns Buffer}
   {:verb "save" :surface :buffer :spi-method :save-buffers
    :doc "Save the focused buffer, or every modified buffer when all is true"
    :params [:map [:all {:optional true} :boolean]]
    :returns [:map [:saved [:vector :string]]]}
   {:verb "goto-line" :surface :buffer :spi-method :goto-line
    :doc "Move the cursor to a 1-indexed line in the focused buffer"
    :params [:map [:line [:int {:min 1}]]]
    :returns [:map [:line :int]]}
   {:verb "insert" :surface :buffer :spi-method :insert-text
    :doc "Insert text at the cursor in the focused buffer"
    :params [:map [:text :string]]
    :returns [:map [:line :int] [:col :int]]}
   {:verb "recent" :surface :buffer :spi-method :recent-files
    :doc "Recently edited files that still exist, most recent first"
    :params NoParams
    :returns [:vector :string]}
   {:verb "project-root" :surface :buffer :spi-method :project-root
    :doc "Root of the focused buffer's project (nearest .git), or null"
    :params NoParams
    :returns [:map [:root [:maybe :string]]]}
   {:verb "context" :surface :buffer :spi-method :editor-context
    :doc "Aggregate snapshot: status, buffers, project root, tab and window counts"
    :params NoParams
    :returns [:map
              [:status Status]
              [:buffers [:vector Buffer]]
              [:project_root [:maybe :string]]
              [:tabs :int]
              [:windows :int]]}])

;;; ============================================================================
;;; Lookups
;;; ============================================================================

(def ^:private by-verb
  (into {} (map (juxt :verb identity)) catalogue))

(def ^:private by-spi-method
  (into {} (map (juxt :spi-method identity)) catalogue))

(defn verb-names
  "Verb names in catalogue order."
  []
  (mapv :verb catalogue))

(defn spec-for
  "The catalogue entry for VERB, or nil."
  [verb]
  (get by-verb verb))

(defn spec-for-spi-method
  "The catalogue entry backing hive-spi method SPI-METHOD, or nil."
  [spi-method]
  (get by-spi-method spi-method))

(defn surface-verbs
  "Verb names backing SURFACE, in catalogue order."
  [surface]
  (into [] (comp (filter #(= surface (:surface %))) (map :verb)) catalogue))

;;; ============================================================================
;;; Params
;;; ============================================================================

(defn- declared-keys
  [params-schema]
  (mapv first (m/children (m/schema params-schema))))

(defn- keywordize
  [params]
  (into {} (map (fn [[k v]] [(keyword (name k)) v])) params))

(defn prepare-params
  "Validate PARAMS for VERB and keep only the keys the verb declares.
   Accepts string or keyword keys. Returns a Result."
  [verb params]
  (if-let [{schema :params} (spec-for verb)]
    (let [declared (declared-keys schema)
          selected (select-keys (keywordize (or params {})) declared)]
      (if (m/validate schema selected)
        (r/ok selected)
        (codec/error "invalid-params"
                     (str verb ": " (s/explain-str schema selected)))))
    (codec/error "unknown-verb"
                 (str "unknown verb " (pr-str verb) ", valid: "
                      (verb-names)))))

(defn check-returns
  "Validate VALUE against VERB's result schema. Returns a Result."
  [verb value]
  (if-let [{schema :returns} (spec-for verb)]
    (if (m/validate schema value)
      (r/ok value)
      (codec/error "vim-error"
                   (str verb " returned a non-conforming value: "
                        (s/explain-str schema value))))
    (codec/error "unknown-verb" (str "unknown verb " (pr-str verb)))))

;;; ============================================================================
;;; Projections
;;; ============================================================================

(defn param-properties
  "JSON-schema properties for every verb param, keyed by string name. Verbs
   that share a param name must agree on its schema."
  []
  (reduce
   (fn [props {schema :params}]
     (reduce (fn [props [k _ child]]
               (assoc props (name k) (mjs/transform child)))
             props
             (m/children (m/schema schema))))
   (sorted-map)
   catalogue))

(defn mcp-input-schema
  "The MCP inputSchema for a consolidated tool: `command` enumerates EXTRA
   commands followed by every verb; every verb param is an optional property."
  [extra-commands command-properties]
  {:type "object"
   :properties (merge {"command" {:type "string"
                                  :enum (into (vec extra-commands) (verb-names))
                                  :description "Vim operation to perform"}}
                      (param-properties)
                      command-properties)
   :required ["command"]})

;;; ============================================================================
;;; Contracts
;;; ============================================================================

(m/=> verb-names [:=> [:cat] [:vector :string]])
(m/=> spec-for [:=> [:cat :any] [:maybe s/VerbSpec]])
(m/=> spec-for-spi-method [:=> [:cat :any] [:maybe s/VerbSpec]])
(m/=> surface-verbs [:=> [:cat s/Surface] [:vector :string]])
(m/=> prepare-params [:=> [:cat :any [:maybe [:map-of [:or :string :keyword] :any]]] s/Result])
(m/=> check-returns [:=> [:cat :any :any] s/Result])
(m/=> param-properties [:=> [:cat] [:map-of :string :map]])
(m/=> mcp-input-schema [:=> [:cat [:sequential :string] [:map-of :string :map]] :map])
