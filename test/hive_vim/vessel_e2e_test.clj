(ns hive-vim.vessel-e2e-test
  "A hive-vessel action reaching a real Vim through the hive-vim executor.

   hive-vessel is unpublished, so it arrives via local.deps.edn:
     clojure -Sdeps \"$(cat local.deps.edn)\" -M:test
   Without it on the classpath this test skips, like the vim/tmux e2e."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-vim.addon :as vim-addon]
            [hive-vim.client :as client]
            [hive-vim.vessel :as vessel])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- resolve-fn
  [sym]
  (try (requiring-resolve sym) (catch Exception _ nil)))

(defn- available?
  []
  (and (resolve-fn 'hive-vessel.core/standard-registry)
       (zero? (:exit (shell/sh "sh" "-c" "command -v vim && command -v tmux")))))

(defn- wait-until
  [timeout-ms pred]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 50) (recur))))))

(defn- temp-dir
  []
  (.getCanonicalFile (.toFile (Files/createTempDirectory "hive-vim-vessel" (make-array FileAttribute 0)))))

(def ^:private vessel-runtime
  "hive-vessel ships its Vim plugin as a resource directory."
  (str (io/file (System/getProperty "user.dir") ".." "hive-vessel" "resources" "hive-vessel" "vim")))

(defn- start-vim!
  [tmux-session workspace port-file]
  (shell/sh "tmux" "new-session" "-d" "-s" tmux-session "-x" "200" "-y" "50"
            "-c" (str workspace)
            (str "vim -N -u NONE -i NONE"
                 " --cmd 'set rtp^=" (.getCanonicalPath (io/file "vim")) "'"
                 " --cmd 'set rtp^=" vessel-runtime "'"
                 " --cmd 'let g:hive_port_file=\"" port-file "\"'"
                 " --cmd 'let g:hive_reconnect_ms=200'"
                 " --cmd 'let g:hive_vessel_headless=1'"
                 " -c 'runtime plugin/hive.vim'"
                 " -c 'runtime plugin/hive_vessel.vim'"
                 " a.txt")))

(deftest hive-vessel-ops-reach-real-vim
  (if-not (available?)
    (println "SKIP hive-vim vessel e2e: hive-vessel, vim or tmux not found")
    (let [standard-registry (resolve-fn 'hive-vessel.core/standard-registry)
          dispatch! (resolve-fn 'hive-vessel.core/dispatch!)
          render-lines (resolve-fn 'hive-vessel.doc/render-lines)
          workspace (temp-dir)
          port-file (str (io/file workspace "vim.port"))
          tmux-session (str "hive-vim-vessel-" (System/nanoTime))
          a (vim-addon/addon-ctor {:vim/port-file port-file})]
      (spit (io/file workspace "a.txt") "line1\nline2\nline3\n")
      (spit (io/file workspace "b.txt") "other\n")
      (try
        (is (:success? (addon/initialize! a {})))
        (start-vim! tmux-session workspace port-file)
        (is (wait-until 10000 #(seq (client/sessions (vim-addon/server a)))) "Vim connected")

        (let [registry (standard-registry)
              target (vessel/vessel-target (vim-addon/server a))
              run (fn [op] (dispatch! registry target op))]

          (testing "the target is a hive-vessel vessel"
            (is (= :vim (:vessel/id target)))
            (is (= :vim-channel (:vessel/dialect target))))

          (testing ":ui/notify"
            (is (:ok (run {:op :ui/notify :message "from hive-vessel" :level :info}))))

          (testing ":ui/open-file moves the real editor"
            (is (:ok (run {:op :ui/open-file :file (str (io/file workspace "b.txt")) :line 1})))
            (let [current (:ok (client/invoke! (vim-addon/server a) {} "current" {}))]
              (is (= (str (io/file workspace "b.txt")) (:name current))
                  "queries and actions address the same Vim")))

          (testing ":ui/show-panel paints the neutral document"
            (let [doc {:doc/title "hive-vim"
                       :doc/blocks [{:block/type :para :text "one standard"}
                                    {:block/type :list :items ["vessel op" "vim channel"]}]}
                  result (run {:op :ui/show-panel :panel/id "hv" :doc doc})]
              (is (:ok result))
              (let [painted (:ok (client/invoke! (vim-addon/server a) {}
                                                 "eval" {:code "hive_vessel#panel_lines('hv')"}))]
                (is (= (mapv :text (render-lines doc)) (:value painted))
                    "Vim painted exactly the rendered lines"))))

          (testing "an executor failure is a loud dispatch error, not a silent drop"
            ;; Translator fallback covers TRANSLATION failures only. A throwing
            ;; executor stops the batch and reports how many ops already ran.
            (let [dead (vessel/vessel-target (vim-addon/server a) {:session "vim-404"})
                  result (dispatch! registry dead
                                    [{:op :ui/notify :message "one"}
                                     {:op :ui/notify :message "two"}])]
              (is (nil? (:ok result)))
              (is (= :execute-threw (get-in result [:error :failure/reason])))
              (is (zero? (get-in result [:error :failure/detail :completed])))))

          (testing "a dialect escape hatch rides the same channel"
            (is (:ok (run {:op :vim/ex :command "let g:hive_vim_e2e = 'ok'"})))
            (is (wait-until 2000
                            #(= {:value "ok"}
                                (:ok (client/invoke! (vim-addon/server a) {}
                                                     "eval" {:code "get(g:, 'hive_vim_e2e', '')"})))))))
        (finally
          (shell/sh "tmux" "kill-session" "-t" tmux-session)
          (addon/shutdown! a)
          (doseq [f (reverse (file-seq workspace))] (.delete ^java.io.File f)))))))
