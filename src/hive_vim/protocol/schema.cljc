(ns hive-vim.protocol.schema
  "HVCP v1 value objects. The bottom layer of hive-vim: every other namespace
   validates against these, and the tests are synthesized from them.

   Host-free cljc. Spec: docs/protocol.md."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; Protocol constants
;;; ============================================================================

(def protocol-version
  "The [major minor] HVCP version this implementation speaks."
  [1 0])

(def dispatch-fn
  "The one Vim function every verb is invoked through."
  "hive#rpc#dispatch")

(def default-timeout-ms 5000)
(def max-timeout-ms 30000)
(def hello-timeout-ms 5000)

;;; ============================================================================
;;; Scalars
;;; ============================================================================

(def RequestId
  "Id of a hive -> Vim request. Negative, so it never collides with Vim's own
   positive message numbers."
  [:int {:max -1}])

(def VimMsgId
  "Id of a Vim -> hive request, assigned by Vim."
  [:int {:min 1}])

(def ProtocolVersion
  [:tuple [:int {:min 0}] [:int {:min 0}]])

(def TimeoutMs
  [:int {:min 1 :max max-timeout-ms}])

(def SessionId
  [:string {:min 1}])

(def wire-err-categories
  "Error categories as they appear on the wire."
  ["invalid-params" "unknown-verb" "vim-error" "not-found" "unsupported"
   "timeout" "disconnected" "protocol-mismatch" "no-session"])

(def WireErrCategory
  (into [:enum] wire-err-categories))

(def ErrCategory
  "Error categories on the hive side: the wire names under the :vim namespace."
  (into [:enum] (map #(keyword "vim" %)) wire-err-categories))

;;; ============================================================================
;;; L0: hive -> Vim wire ops (closed set)
;;; ============================================================================

(def WireOp
  [:multi {:dispatch :op}
   [:call [:map {:closed true}
           [:op [:= :call]]
           [:fn [:string {:min 1}]]
           [:args [:vector :any]]
           [:id {:optional true} RequestId]]]
   [:expr [:map {:closed true}
           [:op [:= :expr]]
           [:expr [:string {:min 1}]]
           [:id {:optional true} RequestId]]]
   [:ex [:map {:closed true}
         [:op [:= :ex]]
         [:cmd [:string {:min 1}]]]]
   [:normal [:map {:closed true}
             [:op [:= :normal]]
             [:keys [:string {:min 1}]]]]
   [:redraw [:map {:closed true}
             [:op [:= :redraw]]
             [:forced? :boolean]]]])

(def Frame
  "One JSON array on the wire, before or after classification."
  [:vector :any])

;;; ============================================================================
;;; L0: Vim -> hive inbound messages
;;; ============================================================================

(def Inbound
  [:multi {:dispatch :kind}
   [:response [:map {:closed true}
               [:kind [:= :response]]
               [:id RequestId]
               [:result :any]]]
   [:request [:map {:closed true}
              [:kind [:= :request]]
              [:id VimMsgId]
              [:method [:string {:min 1}]]
              [:params :any]]]
   [:invalid [:map {:closed true}
              [:kind [:= :invalid]]
              [:frame :any]
              [:reason [:string {:min 1}]]]]])

;;; ============================================================================
;;; L1: dispatch envelope and hive-side Result
;;; ============================================================================

(def WireErr
  [:map
   [:category WireErrCategory]
   [:message :string]])

(def Envelope
  "What hive#rpc#dispatch returns: exactly one of ok or err."
  [:or
   [:map {:closed true} [:ok :any]]
   [:map {:closed true} [:err WireErr]]])

(def Err
  [:map
   [:error ErrCategory]
   [:message :string]])

(def Result
  [:or [:map [:ok :any]] Err])

;;; ============================================================================
;;; L2: handshake
;;; ============================================================================

(def Hello
  [:map
   [:protocol ProtocolVersion]
   [:client [:string {:min 1}]]
   [:vim_version :int]
   [:pid :int]
   [:cwd :string]
   [:verbs [:vector :string]]])

(def HelloReply
  [:multi {:dispatch :accepted}
   [true [:map {:closed true}
          [:accepted [:= true]]
          [:session SessionId]
          [:protocol ProtocolVersion]]]
   [false [:map {:closed true}
           [:accepted [:= false]]
           [:reason [:string {:min 1}]]]]])

;;; ============================================================================
;;; L3: verb catalogue entries
;;; ============================================================================

(def Surface
  "The host contract a verb backs: a hive-spi editor protocol, or hive-addon's
   ITerminalAddon."
  [:enum :substrate :buffer :terminal])

(def VerbSpec
  [:map {:closed true}
   [:verb [:string {:min 1}]]
   [:doc [:string {:min 1}]]
   [:params :any]
   [:returns :any]
   [:surface Surface]
   [:spi-method :keyword]])

;;; ============================================================================
;;; L4: events
;;; ============================================================================

(def event-types ["buf-enter" "buf-write" "focus" "vim-leave"])

(def Event
  [:map [:type (into [:enum] event-types)]])

;;; ============================================================================
;;; Sessions
;;; ============================================================================

(def SessionInfo
  [:map
   [:session SessionId]
   [:hello Hello]
   [:connected-at :int]
   [:last-active :int]])

;;; ============================================================================
;;; Validation
;;; ============================================================================

(defn explain-str
  "Humanized explanation of why VALUE does not conform to SCHEMA, or nil."
  [schema value]
  (some-> (m/explain schema value) me/humanize pr-str))

(defn validate!
  "Return VALUE when it conforms to SCHEMA; throw ex-info naming LABEL otherwise."
  [label schema value]
  (if (m/validate schema value)
    value
    (throw (ex-info (str "HVCP value does not conform: " label)
                    {:label label
                     :explain (explain-str schema value)}))))
