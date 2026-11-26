(ns hive-vim.root-tool-contract-test
  (:require [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-addon.protocol :as proto]
            [hive-vim.addon :as a]))

(deftest-root-tools root-tools
  (let [addon (a/addon-ctor {:vim/port-file (str (java.io.File/createTempFile "hive-vim-rtc" ".port"))})]
    (proto/initialize! addon {})
    (try (proto/tools addon)
         (finally (proto/shutdown! addon)))))
