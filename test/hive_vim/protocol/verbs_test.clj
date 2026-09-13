(ns hive-vim.protocol.verbs-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-dsl.result :as r]
            [hive-spi.editor.ports :as ports]
            [hive-vim.protocol.schema :as s]
            [hive-vim.protocol.verbs :as verbs]
            [malli.core :as m]
            [malli.generator :as mg]
            [hive-addon.terminal :as term]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(deftest catalogue-entries-conform
  (doseq [spec verbs/catalogue]
    (is (m/validate s/VerbSpec spec) (:verb spec))
    (is (m/schema (:params spec)) (str (:verb spec) " params compile"))
    (is (m/schema (:returns spec)) (str (:verb spec) " returns compile"))))

(deftest verbs-and-methods-are-unique
  (is (apply distinct? (map :verb verbs/catalogue)))
  (is (apply distinct? (map :spi-method verbs/catalogue))))

(defn- protocol-methods
  [protocol]
  (set (keys (:sigs protocol))))

(deftest catalogue-covers-the-spi-exactly
  (testing "the substrate verbs back every IEditorPort method, and only those"
    (is (= (protocol-methods ports/IEditorPort)
           (set (map :spi-method (filter #(= :substrate (:surface %))
                                         verbs/catalogue))))))
  (testing "the buffer verbs back every IEditorBufferPort method, and only those"
    (is (= (protocol-methods ports/IEditorBufferPort)
           (set (map :spi-method (filter #(= :buffer (:surface %))
                                         verbs/catalogue))))))
  (testing "the terminal verbs back every ITerminalAddon call to Vim, plus terminal-read"
    (is (= (conj (disj (protocol-methods term/ITerminalAddon) :terminal-id) :terminal-read)
           (set (map :spi-method (filter #(= :terminal (:surface %))
                                         verbs/catalogue)))))))

(def ^:private gen-verb-and-params
  (gen/bind (gen/elements verbs/catalogue)
            (fn [{:keys [verb params]}]
              (gen/fmap (fn [p] [verb p]) (mg/generator params)))))

(defspec valid-params-survive-preparation 200
  (prop/for-all [[verb params] gen-verb-and-params]
    (= (r/ok (select-keys params (map first (m/children (m/schema (:params (verbs/spec-for verb)))))))
       (verbs/prepare-params verb params))))

(defspec string-keys-and-extra-keys-are-normalized 100
  (prop/for-all [[verb params] gen-verb-and-params]
    (let [stringly (into {"command" verb "directory" "/tmp"}
                         (map (fn [[k v]] [(name k) v]))
                         params)
          prepared (verbs/prepare-params verb stringly)]
      (and (r/ok? prepared)
           (not (contains? (:ok prepared) :command))
           (every? keyword? (keys (:ok prepared)))))))

(deftest invalid-and-unknown-are-typed-errors
  (is (= :vim/invalid-params (:error (verbs/prepare-params "goto-line" {:line 0}))))
  (is (= :vim/invalid-params (:error (verbs/prepare-params "find" {}))))
  (is (= :vim/unknown-verb (:error (verbs/prepare-params "nope" {})))))

(deftest check-returns-guards-vim-output
  (is (r/ok? (verbs/check-returns "goto-line" {:line 3})))
  (is (= :vim/vim-error (:error (verbs/check-returns "goto-line" {:line "3"})))))

(deftest mcp-input-schema-projection
  (let [schema (verbs/mcp-input-schema ["sessions" "help"] {"session" {:type "string"}})
        enum (get-in schema [:properties "command" :enum])]
    (is (= ["command"] (:required schema)))
    (is (= ["sessions" "help"] (take 2 enum)))
    (is (= (verbs/verb-names) (drop 2 enum)))
    (is (= {:type "string"} (get-in schema [:properties "session"])))
    (testing "every declared param is advertised"
      (doseq [{:keys [params]} verbs/catalogue
              [k] (m/children (m/schema params))]
        (is (contains? (:properties schema) (name k)) (name k))))))
