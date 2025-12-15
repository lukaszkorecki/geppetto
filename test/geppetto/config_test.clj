(ns geppetto.config-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [geppetto.config :as config]
            [geppetto.errors :as errors]
            [babashka.fs :as fs]))

(defn suppress-exit-fixture [f]
  (binding [errors/*really-exit?* false]
    (f)))

(use-fixtures :each suppress-exit-fixture)

(deftest verify-test
  (testing "with a valid config"
    (let [valid-config {:tasks [{:name "task-1" :command "command-1"}]}]
      (is (= valid-config (config/verify! valid-config)))))

  (testing "with an invalid config"
    (let [invalid-config {:tasks [{:name "task-1"}]}]
      (is (thrown? clojure.lang.ExceptionInfo (config/verify! invalid-config))))))

(deftest resolve-task-dir-test
  (let [temp-dir (fs/create-temp-dir {:prefix "geppetto-test"})
        config-file-dir (str temp-dir)]
    (testing "when :dir is not present"
      (let [task {:name "task-1" :command "command-1"}]
        (is (= task (#'config/resolve-task-dir task {:config-file-dir config-file-dir})))))

    (testing "when :dir is absolute and exists"
      (let [task {:name "task-1" :command "command-1" :dir (str temp-dir)}]
        (is (= task (#'config/resolve-task-dir task {:config-file-dir config-file-dir})))))

    (testing "when :dir is absolute and does not exist"
      (let [task {:name "task-1" :command "command-1" :dir "/non-existent-dir"}]
        (is (thrown? clojure.lang.ExceptionInfo (#'config/resolve-task-dir task {:config-file-dir config-file-dir})))))

    (testing "when :dir is relative and resolves correctly"
      (let [task {:name "task-1" :command "command-1" :dir "subdir"}
            _ (fs/create-dir (fs/path config-file-dir "subdir"))
            resolved-task (#'config/resolve-task-dir task {:config-file-dir config-file-dir})]
        (is (= (str (fs/absolutize (fs/path config-file-dir "subdir"))) (:dir resolved-task)))))

    (testing "when :dir is relative and does not resolve"
      (let [task {:name "task-1" :command "command-1" :dir "non-existent-subdir"}]
        (is (thrown? clojure.lang.ExceptionInfo (#'config/resolve-task-dir task {:config-file-dir config-file-dir})))))))

(deftest parse-env-file-test
  (testing "parses a .env file correctly"
    (let [expected-env {"VAR1" "value1"
                        "VAR2" "\"value2\""
                        "VAR3" "value3"}]
      (is (= expected-env (#'config/parse-env-file "test/fixtures/test.env"))))))

(deftest resolve-env-test
  (let [temp-dir (fs/create-temp-dir {:prefix "geppetto-test"})
        config-file-dir (str temp-dir)]
    (fs/copy "test/fixtures/test.env" (str temp-dir "/test.env"))

    (testing "when env_file exists"
      (let [task {:name "task-1"
                  :command "command-1"
                  :env_file "test.env"
                  :env {:EXISTING_VAR "some-value"}}
            resolved-task (config/resolve-env task {:config-file-dir config-file-dir})
            expected-env {:EXISTING_VAR "some-value"
                          "VAR1" "value1"
                          "VAR2" "\"value2\""
                          "VAR3" "value3"}]
        (is (= expected-env (:env resolved-task)))))

    (testing "when env_file does not exist"
      (let [task {:name "task-1" :command "command-1" :env_file "non-existent.env"}]
        (is (thrown? clojure.lang.ExceptionInfo (config/resolve-env task {:config-file-dir config-file-dir})))))))

(deftest load-test
  (testing "with a valid config"
    (let [config (config/load! "test/fixtures/valid_config.yaml")]
      (is (= 2 (count (:tasks config))))
      (is (= "task-1" (-> config :tasks first :name)))
      (is (= (set ["a" "b"]) (-> config :tasks second :tags)))))

  (testing "with a non-existent config"
    (is (thrown? clojure.lang.ExceptionInfo (config/load! "test/fixtures/non-existent.yaml"))))

  (testing "with an invalid yaml config"
    (is (thrown? Exception (config/load! "test/fixtures/invalid_yaml.yaml"))))

  (testing "with an invalid config"
    (is (thrown? clojure.lang.ExceptionInfo (config/load! "test/fixtures/invalid_config.yaml")))))
