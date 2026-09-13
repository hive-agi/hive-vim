(ns hive-vim.tools.vim-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-vim.fake-vim :as fake]
            [hive-vim.protocol.verbs :as verbs]
            [hive-vim.tools.vim :as tool]
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
        (finally (t/stop! server))))))

(defn- wait-sessions
  [n]
  (loop [i 0]
    (when (and (< i 200) (not= n (count (t/sessions *server*))))
      (Thread/sleep 10)
      (recur (inc i)))))

(deftest tool-schema-is-projected-from-the-catalogue
  (let [{:keys [name inputSchema]} (tool/tool-def (constantly *server*))]
    (is (= "vim" name))
    (is (= (into tool/extra-commands (verbs/verb-names))
           (get-in inputSchema [:properties "command" :enum])))
    (is (contains? (:properties inputSchema) "session"))
    (is (contains? (:properties inputSchema) "timeout_ms"))))

(deftest handler-dispatches-commands
  (let [handler (tool/make-handler (constantly *server*))
        vim (fake/start! *server*)]
    (wait-sessions 1)
    (testing "help lists every verb"
      (let [text (:text (handler {:command "help"}))]
        (doseq [v (verbs/verb-names)]
          (is (re-find (re-pattern (str "- " v ":")) text)))))
    (testing "sessions"
      (is (= [(:session vim)]
             (map :session (json/read-str (:text (handler {"command" "sessions"})) :key-fn keyword)))))
    (testing "a verb with string keys, as MCP delivers them"
      (let [response (handler {"command" "goto-line" "line" 4})]
        (is (not (:isError response)))
        (is (= ["goto-line" {:line 4}] (last @(:calls vim))))))
    (testing "unknown commands"
      (is (re-find #"^\[unknown-verb\]" (:text (handler {:command "rm"})))))
    (fake/stop! vim)))
