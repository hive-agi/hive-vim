(ns hive-vim.editor.port-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.editor.ports :as ports]
            [hive-spi.editor.registry :as registry]
            [hive-vim.editor.port :as port]
            [hive-vim.fake-vim :as fake]
            [hive-vim.protocol.verbs :as verbs]
            [hive-vim.transport :as t]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def ^:dynamic *server* nil)

(use-fixtures :each
  (fn [f]
    (let [server (t/start! {})]
      (try
        (binding [*server* server] (f))
        (finally
          (t/stop! server)
          (port/unregister!))))))

(defn- wait-sessions
  [n]
  (loop [i 0]
    (when (and (< i 200) (not= n (count (t/sessions *server*))))
      (Thread/sleep 10)
      (recur (inc i)))))

(defn- body
  [response]
  (json/read-str (:text response) :key-fn keyword))

(def ^:private method-fns
  "Every substrate and buffer SPI method as a callable."
  {:editor-eval ports/editor-eval :editor-notify ports/editor-notify
   :editor-status ports/editor-status :editor-capabilities ports/editor-capabilities
   :list-buffers ports/list-buffers :current-buffer ports/current-buffer
   :buffer-info ports/buffer-info :special-buffers ports/special-buffers
   :switch-buffer ports/switch-buffer :find-file ports/find-file
   :save-buffers ports/save-buffers :goto-line ports/goto-line
   :insert-text ports/insert-text :recent-files ports/recent-files
   :project-root ports/project-root :editor-context ports/editor-context})

(deftest registers-under-vim-and-implements-every-surface
  (let [p (port/register! (constantly *server*))]
    (is (identical? p (registry/get-port :vim)))
    (is (= #{:buffer :daemon} (registry/surfaces p)))
    (is (not= p (get (registry/registered-ports) :default)))))

(deftest every-spi-method-reaches-vim-through-its-verb
  (let [vim (fake/start! *server*)
        p (port/->port (constantly *server*))
        editor-verbs (filter #(#{:substrate :buffer} (:surface %)) verbs/catalogue)]
    (wait-sessions 1)
    (is (= (set (keys method-fns)) (set (map :spi-method editor-verbs))))
    (doseq [{:keys [verb spi-method params]} editor-verbs
            :let [args (malli.generator/generate params {:seed 5 :size 2})
                  response ((method-fns spi-method) p args)]]
      (is (not (:isError response)) (str spi-method " " (:text response)))
      (is (= (fake/sample-result verb) (body response)) (name spi-method))
      (is (= verb (first (last @(:calls vim)))) (name spi-method)))
    (fake/stop! vim)))

(deftest errors-are-isError-responses
  (let [p (port/->port (constantly *server*))]
    (testing "no session connected"
      (is (re-find #"^\[no-session\]" (:text (ports/editor-status p {})))))
    (testing "invalid params"
      (let [vim (fake/start! *server*)]
        (wait-sessions 1)
        (is (:isError (ports/goto-line p {:line 0})))
        (fake/stop! vim)))))

(deftest daemon-surface-is-the-session-set
  (let [a (fake/start! *server*)
        b (fake/start! *server*)
        p (port/->port (constantly *server*))]
    (wait-sessions 2)
    (is (= #{"vim-1" "vim-2"} (set (map :session (body (ports/list-daemons p {}))))))
    (testing "select-daemon pins later calls"
      (is (not (:isError (ports/select-daemon p {:name (:session a)}))))
      (ports/editor-status p {})
      (is (= 1 (count @(:calls a))))
      (is (empty? @(:calls b))))
    (testing "loud failures"
      (is (:isError (ports/select-daemon p {})))
      (is (:isError (ports/select-daemon p {:name "vim-9"})))
      (is (:isError (ports/kill-daemon p {})))
      (is (re-find #"not-found" (:text (ports/kill-daemon p {:name "vim-9"}))))
      (is (:isError (ports/spawn-daemon p {}))))
    (testing "health reports every session"
      (is (= {:status "ok"} (select-keys (:vim-1 (body (ports/daemon-health p {}))) [:status]))))
    (fake/stop! a)
    (fake/stop! b)))
