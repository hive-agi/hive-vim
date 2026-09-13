(ns hive-vim.terminal-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-addon.protocol :as addon]
            [hive-addon.terminal :as term]
            [hive-vim.fake-vim :as fake]
            [hive-vim.terminal :as terminal]
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

(defn- terminal-vim
  "A fake Vim whose terminal verbs succeed, answering STATUS for terminal-status."
  [status]
  (fake/start! *server*
               (fn [verb {:keys [id]}]
                 (case verb
                   "terminal-spawn" {:ok {:id id :buffer 7 :name (str "hive:" id)}}
                   "terminal-dispatch" {:ok {:sent true}}
                   "terminal-status" {:ok {:id id :status status}}
                   "terminal-kill" {:ok {:killed true}}
                   "terminal-interrupt" {:ok {:interrupted true}}
                   "terminal-read" {:ok {:lines ["$ echo hi" "hi"]}}
                   {:err {:category "unknown-verb" :message verb}}))))

(deftest pure-mappings
  (testing "spawn params"
    (is (= {:id "l1" :cmd ["claude"]}
           (terminal/spawn-params {:id "l1"} {} ["claude"])))
    (is (= {:id "l1" :cmd ["sh" "-l"] :cwd "/w" :env {"A" "1" "B" "x"}}
           (terminal/spawn-params {:id "l1" :cwd "/w"}
                                  {:command ["sh" "-l"] :env {:A 1 "B" "x"}}
                                  ["claude"])))
    (is (not (contains? (terminal/spawn-params {:id "l1"} {:env {}} ["c"]) :env))))
  (testing "results"
    (is (= {:slave/id "l1" :slave/status :running}
           (terminal/status->slave "l1" {:ok {:id "l1" :status "running"}})))
    (is (nil? (terminal/status->slave "l1" {:error :vim/not-found :message "no terminal l1"})))
    (is (= {:killed? false :id "l1" :reason :not-found}
           (terminal/kill->result "l1" {:error :vim/not-found :message "no terminal l1"})))
    (is (= {:success? false :ling-id "l1" :errors ["terminal has finished"]}
           (terminal/interrupt->result "l1" {:error :vim/unsupported
                                             :message "terminal has finished"}))))
  (is (= "hive:l1" (terminal/terminal-name "l1"))))

(deftest protocol-round-trip
  (let [vim (terminal-vim "running")
        tm (terminal/vim-terminal (constantly *server*))
        ctx {:id "ling-1" :cwd "/work" :project-id "p"}]
    (wait-sessions 1)
    (is (satisfies? term/ITerminalAddon tm))
    (is (addon/addon? tm))
    (is (= :vim (term/terminal-id tm)))
    (is (= "ling-1" (term/terminal-spawn! tm ctx {:task "hi"})))
    (is (= ["terminal-spawn" {:id "ling-1" :cmd ["claude"] :cwd "/work"}]
           (last @(:calls vim))))
    (is (= {:cwd "/work" :project-id "p" :session (:session vim)}
           (terminal/context-of tm "ling-1")))
    (is (true? (term/terminal-dispatch! tm ctx {:task "do the thing"})))
    (is (= ["terminal-dispatch" {:id "ling-1" :text "do the thing"}] (last @(:calls vim))))
    (is (= {:slave/id "ling-1" :slave/status :running} (term/terminal-status tm ctx nil)))
    (is (= {:success? true :ling-id "ling-1"} (term/terminal-interrupt! tm ctx)))
    (is (= ["$ echo hi" "hi"] (terminal/read-lines tm "ling-1")))
    (is (= {:killed? true :id "ling-1"} (term/terminal-kill! tm ctx)))
    (is (nil? (terminal/context-of tm "ling-1")) "kill forgets the ling")
    (is (= 0 (get-in (addon/health tm) [:details :terminals])))
    (fake/stop! vim)))

(deftest lings-stay-on-the-vim-that-spawned-them
  (let [a (terminal-vim "running")
        b (terminal-vim "running")]
    (wait-sessions 2)
    (let [pinned (terminal/vim-terminal (constantly *server*) :vim {:session (:session b)})]
      (term/terminal-spawn! pinned {:id "l1"} {})
      (let [roaming (assoc pinned :session nil)]
        (term/terminal-dispatch! roaming {:id "l1"} {:task "x"}))
      (is (empty? (filter #(= "terminal-dispatch" (first %)) @(:calls a))))
      (is (= 1 (count (filter #(= "terminal-dispatch" (first %)) @(:calls b))))))
    (fake/stop! a)
    (fake/stop! b)))

(deftest failures
  (testing "no transport: spawn throws with the ling id"
    (let [tm (terminal/vim-terminal (constantly nil))
          e (try (term/terminal-spawn! tm {:id "l1"} {}) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= "l1" (:id (ex-data e))))
      (is (= :vim/no-session (:error (ex-data e))))))
  (testing "Vim refuses: dispatch throws, status is nil, kill and interrupt report"
    (let [vim (fake/start! *server* (fn [_ _] {:err {:category "not-found" :message "no terminal l1"}}))
          tm (terminal/vim-terminal (constantly *server*))]
      (wait-sessions 1)
      (is (thrown? clojure.lang.ExceptionInfo (term/terminal-dispatch! tm {:id "l1"} {:task "x"})))
      (is (nil? (term/terminal-status tm {:id "l1"} nil)))
      (is (= {:killed? false :id "l1" :reason :not-found} (term/terminal-kill! tm {:id "l1"})))
      (is (false? (:success? (term/terminal-interrupt! tm {:id "l1"}))))
      (is (= {:error :vim/not-found} (terminal/read-lines tm "l1")))
      (fake/stop! vim))))
