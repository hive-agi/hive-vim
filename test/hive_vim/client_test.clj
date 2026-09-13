(ns hive-vim.client-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-dsl.result :as r]
            [hive-vim.client :as client]
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
        (finally (t/stop! server))))))

(defn- wait-sessions
  [n]
  (loop [i 0]
    (when (and (< i 200) (not= n (count (client/sessions *server*))))
      (Thread/sleep 10)
      (recur (inc i)))))

(deftest every-verb-round-trips-through-dispatch
  (let [vim (fake/start! *server*)]
    (wait-sessions 1)
    (doseq [{:keys [verb params]} verbs/catalogue
            :let [args (malli.generator/generate params {:seed 3 :size 2})]]
      (is (= (r/ok (fake/sample-result verb))
             (client/invoke! *server* {:timeout-ms 2000} verb args))
          verb))
    (testing "only declared params reach Vim"
      (client/invoke! *server* {} "find" {"file" "a.txt" "command" "find" "directory" "/x"})
      (is (= ["find" {:file "a.txt"}] (last @(:calls vim)))))
    (fake/stop! vim)))

(deftest invalid-params-never-reach-vim
  (let [vim (fake/start! *server*)]
    (wait-sessions 1)
    (is (= :vim/invalid-params (:error (client/invoke! *server* {} "goto-line" {:line -1}))))
    (is (= :vim/unknown-verb (:error (client/invoke! *server* {} "rm-rf" {}))))
    (is (empty? @(:calls vim)))
    (fake/stop! vim)))

(deftest vim-errors-surface-with-their-category
  (let [vim (fake/start! *server* (fn [_ _] {:err {:category "not-found"
                                                   :message "no buffer"}}))]
    (wait-sessions 1)
    (is (= {:error :vim/not-found :message "no buffer"}
           (client/invoke! *server* {} "buffer-info" {:buffer_name "x"})))
    (fake/stop! vim)))

(deftest non-conforming-results-are-rejected
  (let [vim (fake/start! *server* (fn [_ _] {:ok {:line "not a number"}}))]
    (wait-sessions 1)
    (is (= :vim/vim-error (:error (client/invoke! *server* {} "goto-line" {:line 2}))))
    (fake/stop! vim)))

(deftest session-selection
  (let [a (fake/start! *server*)
        b (fake/start! *server*)]
    (wait-sessions 2)
    (client/invoke! *server* {:session (:session b)} "status" {})
    (is (= 1 (count @(:calls b))))
    (is (empty? @(:calls a)))
    (is (= :vim/no-session (:error (client/invoke! *server* {:session "vim-99"} "status" {}))))
    (fake/stop! a)
    (fake/stop! b)))

(deftest no-transport-is-a-typed-error
  (is (= :vim/no-session (:error (client/invoke! nil {} "status" {}))))
  (is (= [] (client/sessions nil)))
  (is (= :vim/no-session (:error (client/invoke! *server* {} "status" {})))))

(deftest timeouts-are-honoured
  (let [vim (fake/start! *server* (fn [_ _] (Thread/sleep 400) {:ok {:shown true}}))]
    (wait-sessions 1)
    (is (= :vim/timeout (:error (client/invoke! *server* {:timeout-ms 50} "notify" {:message "x"}))))
    (fake/stop! vim)))
