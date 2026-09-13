(ns hive-vim.protocol.codec-test
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-schemas.test :refer [deftrifecta-from-schema]]
            [hive-vim.protocol.codec :as codec]
            [hive-vim.protocol.schema :as s]
            [malli.core :as m]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

;;; ============================================================================
;;; Synthesized from the schemas
;;; ============================================================================

(deftrifecta-from-schema encode-op
  hive-vim.protocol.codec/encode-op
  {:in s/WireOp
   :out s/Frame
   :rel (fn [in out] (= (name (:op in)) (first out)))
   :mutation false
   :num-tests 200})

(deftrifecta-from-schema next-id
  hive-vim.protocol.codec/next-id
  {:in :int
   :out s/RequestId
   :rel (fn [in out] (and (neg? out) (< out (min 0 in))))
   :mutation false
   :num-tests 200})

(deftrifecta-from-schema classify
  hive-vim.protocol.codec/classify
  {:in [:or s/Frame :any]
   :out s/Inbound
   :rel (fn [in out]
          (case (:kind out)
            :response (= in [(:id out) (:result out)])
            :request (= in [(:id out) [(:method out) (:params out)]])
            :invalid (= in (:frame out))))
   :mutation true
   :num-tests 300})

(deftrifecta-from-schema envelope->result
  hive-vim.protocol.codec/envelope->result
  {:in [:or s/Envelope :any]
   :out s/Result
   :rel (fn [in out]
          (if (m/validate [:map {:closed true} [:ok :any]] in)
            (= out {:ok (:ok in)})
            (r/err? out)))
   :mutation true
   :num-tests 300})

(deftrifecta-from-schema hello-reply
  hive-vim.protocol.codec/hello-reply
  {:in [:cat s/Hello s/SessionId]
   :out s/HelloReply
   :rel (fn [[hello session] out]
          (if (= (first (:protocol hello)) (first s/protocol-version))
            (and (:accepted out) (= session (:session out)))
            (false? (:accepted out))))
   :mutation true
   :num-tests 200})

;;; ============================================================================
;;; What the schemas cannot state
;;; ============================================================================

(deftest classify-table
  (testing "responses carry negative ids"
    (is (= {:kind :response :id -3 :result {:ok 1}}
           (codec/classify [-3 {:ok 1}]))))
  (testing "Vim requests are [n [method params]] with n > 0"
    (is (= {:kind :request :id 4 :method "event" :params {:type "focus"}}
           (codec/classify [4 ["event" {:type "focus"}]]))))
  (testing "malformed frames are invalid, never thrown"
    (doseq [frame ["x" nil [] [1] [0 "x"] [5 "hello"] [5 [:hello {}]] ["a" 1]]]
      (is (= :invalid (:kind (codec/classify frame))) (pr-str frame)))))

(deftest dispatch-frame-shape
  (is (= ["call" "hive#rpc#dispatch" ["buffers" {}] -9]
         (codec/encode-op (codec/dispatch-op "buffers" {} -9)))))

(deftest envelope-errors-keep-their-category
  (is (= {:error :vim/not-found :message "no such buffer"}
         (codec/envelope->result {:err {:category "not-found"
                                        :message "no such buffer"}})))
  (testing "an unknown category degrades to vim-error"
    (is (= :vim/vim-error
           (:error (codec/envelope->result {:err {:category "nope" :message ""}})))))
  (testing "Vim's own ERROR string is not a success"
    (is (= :vim/vim-error (:error (codec/envelope->result "ERROR"))))))

(deftest ids-strictly-decrease
  (is (= [-1 -2 -3 -4] (take 4 (rest (iterate codec/next-id 0))))))
