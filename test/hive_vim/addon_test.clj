(ns hive-vim.addon-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-addon.vessel :as hv]
            [hive-spi.editor.registry :as registry]
            [hive-vim.addon :as vim-addon]
            [hive-vim.fake-vim :as fake]
            [hive-vim.transport :as t]
            [hive-vim.vessel :as vessel])
  (:import [java.io File]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(defn- temp-port-file
  []
  (let [f (File/createTempFile "hive-vim-addon" ".port")]
    (.delete f)
    (str f)))

(defn- wait-until
  [pred]
  (loop [i 0]
    (cond (pred) true
          (> i 200) false
          :else (do (Thread/sleep 10) (recur (inc i))))))

(deftest construction-is-pure
  (let [a (vim-addon/addon-ctor {:vim/port-file (temp-port-file)})]
    (is (addon/addon? a))
    (is (= "hive.vim" (addon/addon-id a)))
    (is (= [] (addon/tools a)))
    (is (= :down (:status (addon/health a))))
    (is (nil? (vim-addon/server a)))
    (is (not (contains? (registry/registered-ports) :vim)))))

(deftest lifecycle
  (let [port-file (temp-port-file)
        events (atom [])
        a (vim-addon/addon-ctor {:vim/port-file port-file
                                 :vim/emit-fn (fn [e p] (swap! events conj [e p]))})]
    (try
      (testing "initialize! starts the transport and registers :vim"
        (let [result (addon/initialize! a {})]
          (is (:success? result))
          (is (= (str (get-in result [:metadata :port])) (.trim ^String (slurp port-file))))
          (is (contains? (registry/registered-ports) :vim))
          (is (= ["vim"] (map :name (addon/tools a))))
          (is (= :degraded (:status (addon/health a))))))
      (testing "initialize! is idempotent"
        (is (:already-initialized? (addon/initialize! a {}))))
      (testing "a connected Vim makes it healthy and reachable through the tool"
        (let [vim (fake/start! (vim-addon/server a))
              handler (:handler (first (addon/tools a)))]
          (is (wait-until #(= :ok (:status (addon/health a)))))
          (is (not (:isError (handler {"command" "status"}))))
          (is (some #(= :vim/connected (first %)) @events))
          (fake/stop! vim)))
      (testing "the vessel descriptor exposes the :vim port"
        (let [iv (vessel/->ivessel (vim-addon/vessel a))]
          (is (hv/vessel? iv))
          (is (= :vim (hv/vessel-id iv)))
          (is (= #{:editor} (hv/capabilities iv)))
          (is (identical? (registry/get-port :vim) (hv/addon iv :editor)))
          (is (nil? (hv/addon iv :terminal)))
          (is (nil? (hv/resolve-context iv "ling-1")))))
      (testing "shutdown! reverses everything"
        (let [server (vim-addon/server a)]
          (addon/shutdown! a)
          (is (not (t/running? server)))
          (is (not (.exists (io/file port-file))))
          (is (not (contains? (registry/registered-ports) :vim)))
          (is (= [] (addon/tools a)))
          (is (= :down (:status (addon/health a))))))
      (testing "shutdown! is idempotent"
        (is (nil? (addon/shutdown! a))))
      (finally
        (addon/shutdown! a)))))

(deftest failed-initialization-leaves-nothing-behind
  (let [blocker (java.net.ServerSocket. 0 50 (java.net.InetAddress/getLoopbackAddress))
        a (vim-addon/addon-ctor {:vim/port (.getLocalPort blocker)
                                 :vim/port-file (temp-port-file)})]
    (try
      (let [result (addon/initialize! a {})]
        (is (false? (:success? result)))
        (is (seq (:errors result)))
        (is (not (contains? (registry/registered-ports) :vim)))
        (is (= :down (:status (addon/health a)))))
      (finally
        (.close blocker)
        (addon/shutdown! a)))))
