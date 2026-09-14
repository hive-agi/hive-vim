(ns hive-vim.vessel-parity-test
  "The standard sample batch (the one hive-vessel.parity-test locks) planned
   for THIS editor's target and golden-locked here, so a hive-vessel bump that
   changes a :vim-channel lowering is a visible diff in this repo. Planning is
   pure: no Vim and no executor are needed."
  (:require [clojure.test :refer [deftest is]]
            [hive-test.golden :refer [deftest-golden]]
            [hive-vessel.core :as v]
            [hive-vessel.doc :as d]
            [hive-vim.vessel :as vessel]))

;; Copyright (C) 2026 Pedro Gomes Branquinho (BuddhiLW) <pedrogbranquinho@gmail.com>
;;
;; SPDX-License-Identifier: MIT

(def sample-doc
  (d/doc "Carto \"Flow\" \\ #3"
         (d/heading "apply write-form")
         (d/para "succeeded" :success)
         (d/fields [["paths" "src/a.clj\nsrc/b.clj"] ["verify" "ok"]])
         (d/items ["one" "two\nmore"])
         (d/code "(defn f [] \"x\")" "clojure")
         (d/diff "@@ -1,2 +1,2 @@\n-(old)\n+(new ü)\n context")
         (d/link "open a" "/tmp/a.clj" 2)))

(def sample-batch
  [{:op :ui/show-panel :panel/id "olympus/tab-2" :doc sample-doc}
   {:op :ui/notify :message "frame 7 applied"}
   {:op :ui/notify :message "boom\nsecond line" :level :error}
   {:op :ui/open-file :file "/tmp/a b.clj" :line 3 :column 7}
   {:op :ui/open-file :file "/tmp/a.clj"}
   {:op :ui/send-to-terminal :terminal "*vterm*" :text "ls -la\n"}
   {:op :ui/close-panel :panel/id "olympus/tab-2"}])

(defn- natives []
  (let [target (dissoc (vessel/vessel-target nil) :vessel/execute!)
        r (v/plan (v/standard-registry) target sample-batch)]
    (is (:ok r) (pr-str (:error r)))
    (mapv :native/payload (get-in r [:ok :plan/ops]))))

(deftest every-sample-op-plans-for-this-editor
  (is (= {:vessel/id :vim :vessel/dialect :vim-channel}
         (select-keys (vessel/vessel-target nil) [:vessel/id :vessel/dialect])))
  (is (= (count sample-batch) (count (natives))))
  (is (every? #(= "call" (first %)) (natives)) "every native is a channel call into the bundled autoload"))

(deftest-golden the-sample-batch-lowers-to-these-channel-calls
  "test/golden/vessel/sample-batch.edn"
  (natives))