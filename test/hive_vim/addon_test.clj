(ns hive-vim.addon-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [hive-addon.protocol :as addon]
            [hive-addon.vessel :as hv]
            [hive-spi.editor.registry :as registry]
            [hive-vim.addon :as vim-addon]
            [hive-vim.fake-vim :as fake]
            [hive-vim.transport :as t]
            [hive-vim.vessel :as vessel]
            [hive-addon.terminal :as term]
            [hive-vessel.core :as v]
            [hive-spi.vessel :as render-port]
            [hive-vessel.renderer :as renderer])
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
        (is (satisfies? render-port/IRenderer a))
        (is (identical? a (get @renderer/renderers "hive.vim")))
        (is (:error (render-port/render! a [{:op :ui/send-to-terminal :text "forbidden"}])))
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
          (is (= #{:terminal :editor} (hv/capabilities iv)))
          (is (identical? (registry/get-port :vim) (hv/addon iv :editor)))
          (is (identical? (vim-addon/terminal a) (hv/addon iv :terminal)))
          (is (nil? (hv/resolve-context iv "ling-1")))))
      (testing "shutdown! reverses everything"
        (let [server (vim-addon/server a)]
          (addon/shutdown! a)
          (is (not (t/running? server)))
        (is (not (contains? @renderer/renderers "hive.vim")))
          (is (not (.exists (io/file port-file))))
          (is (not (contains? (registry/registered-ports) :vim)))
          (is (= [] (addon/tools a)))
          (is (= :down (:status (addon/health a))))))
      (testing "shutdown! is idempotent"
        (is (nil? (addon/shutdown! a))))
      (finally
        (addon/shutdown! a)))))

(defn- stub-dependency
  "A mounted sibling addon that ships one :vessel/translators hook, as
   hive.carto-flow does. hive.vim never names it."
  [translators]
  (reify addon/IAddon
    (addon-id [_] "stub.translators")
    (addon-type [_] :native)
    (capabilities [_] #{})
    (initialize! [_ _] {:success? true :errors []})
    (shutdown! [_] nil)
    (tools [_] [])
    (schema-extensions [_] [])
    (health [_] {:status :ok})
    (excluded-tools [_] #{})
    (hooks [_] {:vessel/translators translators})))

(deftest composes-through-hooks
  (let [hello {:translator/id :stub/hello
               :translator/op :stub/hello
               :translator/translate (fn [{:keys [who]} _]
                                       {:op :ui/notify :message (str "hello " who) :level :info})}
        events (atom [])
        a (vim-addon/addon-ctor {:vim/port-file (temp-port-file)})]
    (is (= {} (addon/hooks a)) "no hooks before initialize!")
    (try
      (let [result (addon/initialize! a {:mount/dependencies
                                         {"stub.translators" (stub-dependency [hello])}})]
        (is (:success? result))
        (is (= (inc (count (:registry/translators (v/standard-registry))))
               (get-in result [:metadata :translators]))
            "standard translators plus the dependency's"))
      (let [hooks (addon/hooks a)]
        (is (= vim-addon/hook-keys (set (keys hooks))))
        (is (every? fn? (vals hooks)))
        (testing "listener seat receives transport events"
          (is (true? ((:vim/register-listener! hooks) :test (fn [e p] (swap! events conj [e p]))))))
        (let [vim (fake/start-raw! (vim-addon/server a) (fn [f args] [f args]))]
          (is (wait-until #(some (fn [[e]] (= :vim/connected e)) @events)))
          (fake/event! vim 9 {:type "focus"})
          (is (wait-until #(some (fn [[e p]] (and (= :vim/event e)
                                                  (= "focus" (get-in p [:event :type]))))
                                 @events)))
          (testing "a dependency's intent lowers onto Vim through :vessel/dispatch!"
            (let [result ((:vessel/dispatch! hooks) {:op :stub/hello :who "vim"})]
              (is (:ok result) (pr-str result))
              (is (= ["hive_vessel#notify" ["hello vim" "info"]] (last @(:calls vim))))))
          (testing "translators registered at runtime join the registry"
            ((:vessel/register-translators! hooks)
             [{:translator/id :later/ping :translator/op :later/ping
               :translator/translate (fn [_ _] {:op :vim/call :fn "Ping" :args [1]})}])
            (is (:ok ((:vessel/dispatch! hooks) {:op :later/ping})))
            (is (= ["Ping" [1]] (last @(:calls vim)))))
          (testing "unregistered listeners stop receiving"
            ((:vim/unregister-listener! hooks) :test)
            (let [n (count @events)]
              (fake/event! vim 10 {:type "focus"})
              (Thread/sleep 100)
              (is (= n (count @events)))))
          (fake/stop! vim))
        (testing "target, vessel instance and terminal are the addon's own"
          (is (= :vim-channel (:vessel/dialect ((:vessel/target hooks)))))
          (let [iv ((:vessel/instance hooks))]
            (is (hv/vessel? iv))
            (is (= #{:editor :terminal} (hv/capabilities iv)))
            (is (identical? ((:vim/terminal hooks)) (hv/addon iv :terminal))))
          (is (= :vim (term/terminal-id ((:vim/terminal hooks)))))
          (is (identical? (registry/get-port :vim) ((:vim/editor-port hooks))))))
      (addon/shutdown! a)
      (is (= {} (addon/hooks a)) "no hooks after shutdown!")
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
