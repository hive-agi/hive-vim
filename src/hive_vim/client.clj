(ns hive-vim.client
  "Invoke HVCP verbs on a connected Vim session.

   One railway per call: prepare params against the catalogue, pick the
   session, send through hive#rpc#dispatch, unwrap the envelope, check the
   result shape. Every step returns a Result; nothing throws."
  (:require [hive-dsl.result :as r]
            [hive-vim.protocol.codec :as codec]
            [hive-vim.protocol.schema :as s]
            [hive-vim.protocol.verbs :as verbs]
            [hive-vim.transport :as transport]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def InvokeOpts
  [:map
   [:session {:optional true} [:maybe :string]]
   [:timeout-ms {:optional true} [:maybe :int]]])

(defn invoke!
  "Run VERB with PARAMS on the session OPTS selects (:session, default the most
   recently active) within :timeout-ms. Returns (ok value) or a :vim/* error."
  [server {:keys [session timeout-ms]} verb params]
  (if-not (and server (transport/running? server))
    (codec/error "no-session" "hive-vim transport is not running")
    (r/let-ok [prepared (verbs/prepare-params verb params)
               session-id (transport/select-session server session)
               raw (transport/request! server session-id
                                       #(codec/dispatch-op verb prepared %)
                                       timeout-ms)
               value (codec/envelope->result raw)]
      (verbs/check-returns verb value))))

(def ^:private replying-commands
  "Channel commands Vim answers when given an id. ex, normal and redraw never
   reply (:help channel-commands)."
  #{"call" "expr"})

(defn- payload->op
  "A hive-vessel :vim-channel payload as a wire op, or nil when malformed."
  [payload]
  (let [[command a b] (when (sequential? payload) (vec payload))]
    (case command
      "call" (when (and (string? a) (sequential? b))
               {:op :call :fn a :args (vec b)})
      "expr" (when (string? a) {:op :expr :expr a})
      "ex" (when (string? a) {:op :ex :cmd a})
      "normal" (when (string? a) {:op :normal :keys a})
      "redraw" {:op :redraw :forced? (= "force" a)}
      nil)))

(defn execute-native!
  "Run a hive-vessel :vim-channel PAYLOAD (`[\"call\" fn args]`, `[\"ex\" cmd]`,
   ...) on the session OPTS selects. Returns (ok value) for a command Vim
   answers, (ok true) for a fire-and-forget one."
  [server {:keys [session timeout-ms]} payload]
  (if-not (and server (transport/running? server))
    (codec/error "no-session" "hive-vim transport is not running")
    (if-let [op (payload->op payload)]
      (r/let-ok [session-id (transport/select-session server session)]
        (if (contains? replying-commands (name (:op op)))
          (transport/request! server session-id #(assoc op :id %) timeout-ms)
          (transport/send! server session-id op)))
      (codec/error "invalid-params"
                   (str "not a :vim-channel payload: " (pr-str payload))))))

(defn sessions
  "Connected sessions, most recently active first. Empty when not running."
  [server]
  (if (and server (transport/running? server))
    (transport/sessions server)
    []))

(m/=> invoke! [:=> [:cat :any InvokeOpts :any :any] s/Result])
(m/=> execute-native! [:=> [:cat :any InvokeOpts :any] s/Result])
(m/=> sessions [:=> [:cat :any] [:vector s/SessionInfo]])
