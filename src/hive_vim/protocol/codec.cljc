(ns hive-vim.protocol.codec
  "HVCP v1 wire codec. Pure: frames in, frames out, no IO.

   Host-free cljc. Spec: docs/protocol.md (L0-L2)."
  (:require [hive-dsl.result :as r]
            [hive-vim.protocol.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; hive -> Vim
;;; ============================================================================

(defn encode-op
  "The wire frame for a hive -> Vim op."
  [{:keys [op id] :as wire-op}]
  (case op
    :call   (cond-> ["call" (:fn wire-op) (:args wire-op)] id (conj id))
    :expr   (cond-> ["expr" (:expr wire-op)] id (conj id))
    :ex     ["ex" (:cmd wire-op)]
    :normal ["normal" (:keys wire-op)]
    :redraw ["redraw" (if (:forced? wire-op) "force" "")]))

(defn dispatch-op
  "The op invoking VERB with PARAMS through the Vim-side dispatch function."
  [verb params id]
  {:op :call :fn s/dispatch-fn :args [verb params] :id id})

(defn next-id
  "The request id that follows LAST-ID. Ids start at -1 and decrease."
  [last-id]
  (dec (min 0 last-id)))

(defn reply-frame
  "The frame answering Vim request ID with PAYLOAD."
  [id payload]
  [id payload])

;;; ============================================================================
;;; Vim -> hive
;;; ============================================================================

(defn- invalid
  [frame reason]
  {:kind :invalid :frame frame :reason reason})

(defn classify
  "Classify one decoded inbound frame as a response, a request or invalid."
  [frame]
  (if-not (and (vector? frame) (= 2 (count frame)) (int? (first frame)))
    (invalid frame "expected [id payload]")
    (let [[id payload] frame]
      (cond
        (neg? id)
        {:kind :response :id id :result payload}

        (zero? id)
        (invalid frame "id 0 is not a request or a response")

        (and (vector? payload)
             (= 2 (count payload))
             (string? (first payload))
             (seq (first payload)))
        {:kind :request :id id :method (first payload) :params (second payload)}

        :else
        (invalid frame "request payload must be [method params]")))))

;;; ============================================================================
;;; Results
;;; ============================================================================

(defn error
  "The hive-side error Result for CATEGORY (a wire name or :vim keyword)."
  [category message]
  (r/err (if (keyword? category) category (keyword "vim" category))
         {:message (str message)}))

(def ^:private wire-category?
  (set s/wire-err-categories))

(defn envelope->result
  "Translate what hive#rpc#dispatch returned into a Result. Anything that is not
   an envelope (Vim's own \"ERROR\" string, a missing plugin) is a :vim/vim-error."
  [value]
  (cond
    (and (map? value) (= #{:ok} (set (keys value))))
    (r/ok (:ok value))

    (and (map? value) (= #{:err} (set (keys value))) (map? (:err value)))
    (let [{:keys [category message]} (:err value)]
      (error (if (wire-category? category) category "vim-error")
             (or message "")))

    :else
    (error "vim-error" (str "not an HVCP envelope: " (pr-str value)))))

;;; ============================================================================
;;; Handshake
;;; ============================================================================

(defn hello-reply
  "Decide the reply to a hello. Accepts when the protocol major matches."
  [hello session-id]
  (let [[major] (:protocol hello)
        [our-major] s/protocol-version]
    (if (= major our-major)
      {:accepted true :session session-id :protocol s/protocol-version}
      {:accepted false
       :reason (str "protocol major " major " unsupported, hive speaks "
                    our-major)})))

;;; ============================================================================
;;; Contracts
;;; ============================================================================

(m/=> encode-op [:=> [:cat s/WireOp] s/Frame])
(m/=> dispatch-op [:=> [:cat [:string {:min 1}] :any s/RequestId] s/WireOp])
(m/=> next-id [:=> [:cat :int] s/RequestId])
(m/=> reply-frame [:=> [:cat s/VimMsgId :any] s/Frame])
(m/=> classify [:=> [:cat :any] s/Inbound])
(m/=> error [:=> [:cat [:or s/ErrCategory s/WireErrCategory] :any] s/Err])
(m/=> envelope->result [:=> [:cat :any] s/Result])
(m/=> hello-reply [:=> [:cat s/Hello s/SessionId] s/HelloReply])
