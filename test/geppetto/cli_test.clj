(ns geppetto.cli-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [geppetto.cli :as cli]
            [geppetto.watchdog :as watchdog]
            [geppetto.test-helper :as helper]))

(use-fixtures :each helper/exit-suppressing-fixture)

(deftest parse-args-test
  (testing "with --help option"
    (let [result (cli/parse-args ["--help" "config.yaml"])]
      (is (= 0 (:exit-code result)))
      (is (clojure.string/includes? (:message result) "Usage: geppetto"))))

  (testing "with --version option"
    (let [result (cli/parse-args ["--version" "config.yaml"])]
      (is (= 0 (:exit-code result)))
      (is (clojure.string/includes? (:message result) "Geppetto version"))))

  (testing "with invalid option"
    (let [result (cli/parse-args ["--invalid-option" "config.yaml"])]
      (is (= 1 (:exit-code result)))
      (is (clojure.string/includes? (:message result) "Error parsing command line options"))))

  (testing "with no config file"
    (let [result (cli/parse-args [])]
      (is (= 1 (:exit-code result)))
      (is (clojure.string/includes? (:message result) "Error: exactly one config file must be specified"))))

  (testing "with valid options"
    (let [result (cli/parse-args ["--exit-mode" "keep-going" "--tasks" "a,b" "--tags" "c,d" "config.yaml"])]
      (is (= ::watchdog/keep-going (:exit-mode result)))
      (is (= #{"a" "b"} (:tasks-to-launch result)))
      (is (= #{"c" "d"} (:tags result)))
      (is (= "config.yaml" (:config-file result))))))

(deftest process-args-test
  (testing "prepends ./ to relative path"
    (let [opts {:config-file "config.yaml"}
          result (cli/process-args opts)]
      (is (= "./config.yaml" (:config-file result)))))

  (testing "does not prepend ./ to absolute path"
    (let [opts {:config-file "/config.yaml"}
          result (cli/process-args opts)]
      (is (= "/config.yaml" (:config-file result)))))

  (testing "does not prepend ./ to ./ path"
    (let [opts {:config-file "./config.yaml"}
          result (cli/process-args opts)]
      (is (= "./config.yaml" (:config-file result)))))

  (testing "calls System/exit on exit-code"
    (helper/assert-exits-with-code 1
                                   (cli/process-args {:exit-code 1 :message "error"})))

  (testing "does not exit when no exit code"
    (let [opts {:config-file "config.yaml"}
          result (cli/process-args opts)]
      (is (= "./config.yaml" (:config-file result))))))

