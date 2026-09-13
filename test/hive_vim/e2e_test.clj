(ns hive-vim.e2e-test
  "HVCP against a real Vim running in tmux. Skips when vim or tmux is absent."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-dsl.result :as r]
            [hive-vim.addon :as vim-addon]
            [hive-vim.client :as client]
            [hive-vim.protocol.schema :as s]
            [hive-vim.protocol.verbs :as verbs])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- available?
  []
  (zero? (:exit (shell/sh "sh" "-c" "command -v vim && command -v tmux"))))

(defn- wait-until
  [timeout-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 50) (recur))))))

(defn- temp-dir
  []
  (.getCanonicalFile (.toFile (Files/createTempDirectory "hive-vim-e2e" (make-array FileAttribute 0)))))

(defn- start-vim!
  [tmux-session workspace port-file]
  (let [runtime (.getCanonicalPath (io/file "vim"))
        cmd (str "vim -N -u NONE -i NONE"
                 " --cmd 'set rtp^=" runtime "'"
                 " --cmd 'let g:hive_port_file=\"" port-file "\"'"
                 " --cmd 'let g:hive_reconnect_ms=200'"
                 " -c 'runtime plugin/hive.vim'"
                 " a.txt")]
    (shell/sh "tmux" "new-session" "-d" "-s" tmux-session "-x" "200" "-y" "50"
              "-c" (str workspace) cmd)))

(defn- ok!
  "Invoke and return the ok value, failing the test with the error otherwise."
  [addon verb params]
  (let [result (client/invoke! (vim-addon/server addon) {:timeout-ms 5000} verb params)]
    (is (r/ok? result) (str verb " " (pr-str result)))
    (:ok result)))

(deftest hvcp-against-real-vim
  (if-not (available?)
    (println "SKIP hive-vim e2e: vim or tmux not found")
    (let [workspace (temp-dir)
          port-file (str (io/file workspace "vim.port"))
          tmux-session (str "hive-vim-e2e-" (System/nanoTime))
          events (atom [])
          config {:vim/port-file port-file
                  :vim/emit-fn (fn [e p] (swap! events conj [e p]))}
          a (vim-addon/addon-ctor config)]
      (.mkdir (io/file workspace ".git"))
      (spit (io/file workspace "a.txt") "line1\nline2\nline3\n")
      (try
        (is (:success? (addon/initialize! a {})))
        (start-vim! tmux-session workspace port-file)
        (is (wait-until 10000 #(seq (client/sessions (vim-addon/server a))))
            "Vim connected")

        (testing "handshake and conformance: Vim implements exactly the catalogue"
          (let [{:keys [hello]} (first (client/sessions (vim-addon/server a)))]
            (is (= s/protocol-version (:protocol hello)))
            (is (= (verbs/verb-names) (:verbs hello))))
          (let [caps (ok! a "capabilities" {})]
            (is (= (verbs/verb-names) (:verbs caps)))
            (is (= s/protocol-version (:protocol caps)))))

        (testing "substrate verbs"
          (is (= 901 (quot (:vim_version (ok! a "status" {})) 1)) "status")
          (is (= {:value 3} (ok! a "eval" {:code "1+2"})))
          (is (= {:value "\nx"} (ok! a "eval" {:code "echo 'x'" :mode "ex"})))
          (is (= :vim/vim-error
                 (:error (client/invoke! (vim-addon/server a) {} "eval" {:code "nosuchfn()"}))))
          (is (= {:shown true} (ok! a "notify" {:message "hello from hive" :level "info"}))))

        (testing "buffer verbs"
          (let [a-txt (str (io/file workspace "a.txt"))]
            (is (= [a-txt] (map :name (ok! a "buffers" {}))))
            (is (= a-txt (:name (ok! a "current" {}))))
            (is (= 3 (:line_count (ok! a "buffer-info" {:buffer_name a-txt}))))
            (is (= :vim/not-found
                   (:error (client/invoke! (vim-addon/server a) {} "buffer-info" {:buffer_name "nope"}))))
            (is (every? #(seq (:buftype %)) (ok! a "special-buffers" {}))
                "special buffers are exactly the non-file ones (notify leaves a popup)")
            (is (= {:line 2} (ok! a "goto-line" {:line 2})))
            (is (= {:line 2 :col 2} (ok! a "insert" {:text "X"})))
            (is (true? (:modified (ok! a "current" {}))))
            (is (= {:saved [a-txt]} (ok! a "save" {})))
            (is (= "line1\nXline2\nline3\n" (slurp a-txt)) "save reached the disk")
            (is (wait-until 3000 #(some (fn [[e p]] (and (= :vim/event e)
                                                         (= "buf-write" (get-in p [:event :type]))))
                                        @events))
                "buf-write event arrived")
            (is (= (str workspace) (:root (ok! a "project-root" {}))))
            (is (vector? (ok! a "recent" {})))
            (let [weird "q'x\" |echo.txt"
                  opened (ok! a "find" {:file weird})]
              (is (= (str (io/file workspace weird)) (:name opened))
                  "a hostile filename is opened literally"))
            (is (= a-txt (:name (ok! a "switch" {:buffer a-txt}))))
            (let [ctx (ok! a "context" {})]
              (is (= 2 (count (:buffers ctx))))
              (is (= (str workspace) (:project_root ctx))))))

        (testing "the tool answers through the same path"
          (let [handler (:handler (first (addon/tools a)))
                response (handler {"command" "goto-line" "line" 3})]
            (is (not (:isError response)) (:text response))
            (is (= {:line 3} (json/read-str (:text response) :key-fn keyword)))))

        (testing "Vim reconnects when hive restarts"
          (addon/shutdown! a)
          (let [b (vim-addon/addon-ctor config)]
            (try
              (is (:success? (addon/initialize! b {})))
              (is (wait-until 10000 #(seq (client/sessions (vim-addon/server b))))
                  "Vim reconnected to the new transport")
              (is (= {:value 7} (:ok (client/invoke! (vim-addon/server b) {} "eval" {:code "3+4"}))))
              (finally
                (addon/shutdown! b)))))
        (finally
          (shell/sh "tmux" "kill-session" "-t" tmux-session)
          (addon/shutdown! a)
          (doseq [f (reverse (file-seq workspace))]
            (.delete ^java.io.File f)))))))
