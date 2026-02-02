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
        (is (= svc (#'config/resolve-service-dir svc {:root-dir config-file-dir})))))

    (testing "when :dir is absolute and exists"
      (let [svc {:name "svc-1" :command "command-1" :dir (str temp-dir)}]
        (is (= svc (#'config/resolve-service-dir svc {:root-dir config-file-dir})))))

    (testing "when :dir is absolute and does not exist"
      (let [svc {:name "svc-1" :command "command-1" :dir "/non-existent-dir"}]
        (is (thrown? clojure.lang.ExceptionInfo (#'config/resolve-service-dir svc {:root-dir config-file-dir})))))

    (testing "when :dir is relative and resolves correctly"
      (let [svc {:name "svc-1" :command "command-1" :dir "subdir"}
            _ (fs/create-dir (fs/path config-file-dir "subdir"))
            resolved-svc (#'config/resolve-service-dir svc {:root-dir config-file-dir})]
        (is (= (str (fs/absolutize (fs/path config-file-dir "subdir"))) (:dir resolved-svc)))))

    (testing "when :dir is relative and does not resolve"
      (let [svc {:name "svc-1" :command "command-1" :dir "non-existent-subdir"}]
        (is (thrown? clojure.lang.ExceptionInfo (#'config/resolve-service-dir svc {:root-dir config-file-dir})))))))

(deftest expand-env-vars-test
  (testing "expands ${VAR} when env var exists"
    (let [original-home (System/getenv "HOME")]
      (is (= (str "path: " original-home)
             (#'config/expand-env-vars "path: ${HOME}")))))

  (testing "expands ${VAR:-default} to value when env var exists"
    (let [original-home (System/getenv "HOME")]
      (is (= original-home
             (#'config/expand-env-vars "${HOME:-/fallback}")))))

  (testing "expands ${VAR:-default} to default when env var missing"
    (is (= "fallback-value"
           (#'config/expand-env-vars "${GEPPETTO_TEST_NONEXISTENT_VAR:-fallback-value}"))))

  (testing "expands missing var without default to empty string"
    (is (= ""
           (#'config/expand-env-vars "${GEPPETTO_TEST_NONEXISTENT_VAR}"))))

  (testing "expands multiple vars in same string"
    (let [home (System/getenv "HOME")
          user (System/getenv "USER")]
      (is (= (str home "/" user)
             (#'config/expand-env-vars "${HOME}/${USER}")))))

  (testing "leaves strings without vars unchanged"
    (is (= "plain string"
           (#'config/expand-env-vars "plain string")))))

(deftest verify-env-types-test
  (testing "env map accepts strings, ints, and booleans"
    (let [config {:services [{:name "svc-1"
                              :command "echo test"
                              :env {:STRING_VAR "hello"
                                    :INT_VAR 42
                                    :BOOL_VAR true}}]}]
      (is (= config (config/verify! config))))))

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
