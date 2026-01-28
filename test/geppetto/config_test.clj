(ns geppetto.config-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [geppetto.config :as config]
            [babashka.fs :as fs]
            [geppetto.test-helper :as helper]))

(use-fixtures :each helper/exit-suppressing-fixture)

(deftest verify-test
  (testing "with a valid config"
    (let [valid-config {:services [{:name "svc-1" :command "command-1"}]}]
      (is (= valid-config (config/verify! valid-config)))))

  (testing "with an invalid config"
    (let [invalid-config {:services [{:name "svc-1"}]}]
      (is (thrown? clojure.lang.ExceptionInfo (config/verify! invalid-config))))))

(deftest resolve-service-dir-test
  (let [temp-dir (fs/create-temp-dir {:prefix "geppetto-test"})
        config-file-dir (str temp-dir)]
    (testing "when :dir is not present"
      (let [svc {:name "svc-1" :command "command-1"}]
        (is (= svc (#'config/resolve-service-dir svc {:config-file-dir config-file-dir})))))

    (testing "when :dir is absolute and exists"
      (let [svc {:name "svc-1" :command "command-1" :dir (str temp-dir)}]
        (is (= svc (#'config/resolve-service-dir svc {:config-file-dir config-file-dir})))))

    (testing "when :dir is absolute and does not exist"
      (let [svc {:name "svc-1" :command "command-1" :dir "/non-existent-dir"}]
        (is (thrown? clojure.lang.ExceptionInfo (#'config/resolve-service-dir svc {:config-file-dir config-file-dir})))))

    (testing "when :dir is relative and resolves correctly"
      (let [svc {:name "svc-1" :command "command-1" :dir "subdir"}
            _ (fs/create-dir (fs/path config-file-dir "subdir"))
            resolved-svc (#'config/resolve-service-dir svc {:config-file-dir config-file-dir})]
        (is (= (str (fs/absolutize (fs/path config-file-dir "subdir"))) (:dir resolved-svc)))))

    (testing "when :dir is relative and does not resolve"
      (let [svc {:name "svc-1" :command "command-1" :dir "non-existent-subdir"}]
        (is (thrown? clojure.lang.ExceptionInfo (#'config/resolve-service-dir svc {:config-file-dir config-file-dir})))))))

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
      (let [svc {:name "svc-1"
                 :command "command-1"
                 :env_file "test.env"
                 :env {:EXISTING_VAR "some-value"}}
            resolved-svc (config/resolve-env svc {:config-file-dir config-file-dir})
            expected-env {:EXISTING_VAR "some-value"
                          "VAR1" "value1"
                          "VAR2" "\"value2\""
                          "VAR3" "value3"}]
        (is (= expected-env (:env resolved-svc)))))

    (testing "when env_file does not exist"
      (let [svc {:name "svc-1" :command "command-1" :env_file "non-existent.env"}]
        (is (thrown? clojure.lang.ExceptionInfo (config/resolve-env svc {:config-file-dir config-file-dir})))))))

(deftest load-test
  (testing "with a valid config"
    (let [config (config/load! "test/fixtures/valid_config.yaml")]
      (is (= 2 (count (:services config))))
      (is (= "svc-1" (-> config :services first :name)))
      (is (= (set ["a" "b"]) (-> config :services second :tags)))))

  (testing "with a non-existent config"
    (is (thrown? clojure.lang.ExceptionInfo (config/load! "test/fixtures/non-existent.yaml"))))

  (testing "with an invalid yaml config"
    (is (thrown? Exception (config/load! "test/fixtures/invalid_yaml.yaml"))))

  (testing "with an invalid config"
    (is (thrown? clojure.lang.ExceptionInfo (config/load! "test/fixtures/invalid_config.yaml")))))
