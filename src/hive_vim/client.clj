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

(defn sessions
  "Connected sessions, most recently active first. Empty when not running."
  [server]
  (if (and server (transport/running? server))
    (transport/sessions server)
    []))

(m/=> invoke! [:=> [:cat :any InvokeOpts :any :any] s/Result])
(m/=> sessions [:=> [:cat :any] [:vector s/SessionInfo]])
