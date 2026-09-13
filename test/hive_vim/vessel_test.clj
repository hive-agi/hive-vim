(ns hive-vim.vessel-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-vim.client :as client]
            [hive-vim.fake-vim :as fake]
            [hive-vim.transport :as t]
            [hive-vim.vessel :as vessel]))

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

(defn- native
  [payload]
  {:op :vessel/native :native/dialect :vim-channel :native/payload payload})

(defn- fake-calls
  "A fake Vim recording every channel call it is asked to answer."
  [server frames]
  (fake/start-raw! server (fn [vim-fn args]
                            (swap! frames conj [vim-fn args])
                            true)))

(deftest target-shape
  (let [target (vessel/vessel-target *server* {:features #{:carto-flow/timeline}})]
    (is (= :vim (:vessel/id target)))
    (is (= :vim-channel (:vessel/dialect target)))
    (is (= #{:carto-flow/timeline} (:vessel/features target)))
    (is (fn? (:vessel/execute! target)))
    (is (not (contains? (vessel/vessel-target *server*) :vessel/features)))))

(deftest call-payloads-round-trip
  (let [vim (fake/start-raw! *server* (fn [f args] {:fn f :args args}))
        target (vessel/vessel-target *server*)]
    (wait-sessions 1)
    (testing "a lowered ui op reaches Vim as its function call"
      (is (= {:fn "hive_vessel#notify" :args ["hi" "info"]}
             ((:vessel/execute! target)
              (native ["call" "hive_vessel#notify" ["hi" "info"]]))))
      (is (= ["hive_vessel#notify" ["hi" "info"]] (last @(:calls vim)))))
    (fake/stop! vim)))

(deftest fire-and-forget-payloads
  (let [frames (atom [])
        vim (fake-calls *server* frames)
        target (vessel/vessel-target *server*)]
    (wait-sessions 1)
    (is (true? ((:vessel/execute! target) (native ["ex" "echo 'x'"]))))
    (is (true? ((:vessel/execute! target) (native ["redraw" ""]))))
    (is (empty? @frames) "no reply is awaited")
    (fake/stop! vim)))

(deftest failures-throw-so-translators-can-fall-through
  (testing "no session"
    (let [target (vessel/vessel-target *server*)]
      (is (thrown? clojure.lang.ExceptionInfo
                   ((:vessel/execute! target) (native ["call" "f" []]))))))
  (testing "a wrong dialect is refused"
    (let [target (vessel/vessel-target *server*)]
      (is (thrown? clojure.lang.ExceptionInfo
                   ((:vessel/execute! target)
                    {:op :vessel/native :native/dialect :elisp :native/payload ["call" "f" []]})))))
  (testing "a malformed payload is refused"
    (let [vim (fake/start! *server*)
          target (vessel/vessel-target *server*)]
      (wait-sessions 1)
      (is (thrown? clojure.lang.ExceptionInfo
                   ((:vessel/execute! target) (native ["call" 42 "nope"]))))
      (fake/stop! vim))))

(deftest sessions-are-selectable
  (let [a (fake/start-raw! *server* (fn [_ _] "a"))
        b (fake/start-raw! *server* (fn [_ _] "b"))]
    (wait-sessions 2)
    (let [to-a (vessel/vessel-target *server* {:session (:session a)})
          to-b (vessel/vessel-target *server* {:session (:session b)})]
      (is (= "a" ((:vessel/execute! to-a) (native ["call" "f" []]))))
      (is (= "b" ((:vessel/execute! to-b) (native ["call" "f" []])))))
    (fake/stop! a)
    (fake/stop! b)))

(deftest raw-client-surface
  (let [vim (fake/start-raw! *server* (fn [f _] f))]
    (wait-sessions 1)
    (is (= {:ok "g"} (client/execute-native! *server* {} ["call" "g" []])))
    (is (= :vim/invalid-params
           (:error (client/execute-native! *server* {} ["nope"]))))
    (is (= :vim/no-session
           (:error (client/execute-native! nil {} ["call" "g" []]))))
    (fake/stop! vim)))
