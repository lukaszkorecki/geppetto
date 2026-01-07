(ns geppetto.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [geppetto.core :as core]
            geppetto.errors
            [geppetto.config :as config]
            [com.stuartsierra.component :as component]
            [geppetto.task :as task]
            [geppetto.test-helper :as helper]))

(use-fixtures :each helper/exit-suppressing-fixture)

(def tasks
  [{:name "task-1" :command "command-1" :tags #{"tag-a"}}
   {:name "task-2" :command "command-2" :tags #{"tag-b"}}
   {:name "task-3" :command "command-3" :tags #{"tag-a" "tag-c"}}
   {:name "task-4" :command "command-4"}])

(deftest build-system-test
  (testing "no filters"
    (let [sys (#'core/build-system {:tasks tasks :tasks-to-launch #{} :tags #{}})]
      (is (= #{:task-1 :task-2 :task-3 :task-4 :watchdog} (set (keys sys))))))

  (testing "filter by name"
    (let [sys (#'core/build-system {:tasks tasks :tasks-to-launch #{"task-1"} :tags #{}})]
      (is (= #{:task-1 :watchdog} (set (keys sys))))))

  (testing "filter by tag"
    (let [sys (#'core/build-system {:tasks tasks :tasks-to-launch #{} :tags #{"tag-a"}})]
      (is (= #{:task-1 :task-3 :task-4 :watchdog} (set (keys sys))))))

  (testing "filter by name and tag"
    (let [sys (#'core/build-system {:tasks tasks :tasks-to-launch #{"task-1"} :tags #{"tag-a"}})]
      (is (= #{:task-1 :watchdog} (set (keys sys))))))

  (testing "filter by non-existent name"
    (is (thrown? clojure.lang.ExceptionInfo
                 (#'core/build-system {:tasks tasks :tasks-to-launch #{"non-existent"} :tags #{}}))))

  (testing "filter by non-existent tag"
    (let [sys (#'core/build-system {:tasks tasks :tasks-to-launch #{} :tags #{"non-existent"}})]
      (is (= #{:task-4 :watchdog} (set (keys sys)))))))

(deftest run-geppetto-test
  (testing "with --print-tasks option"
    (with-redefs [config/load! (fn [_] {:tasks tasks})
                  component/start-system (fn [sys] sys)
                  task/create (fn [t] t)]
      (let [s (with-out-str
                (is (thrown? clojure.lang.ExceptionInfo
                             (core/run-geppetto ["--print-tasks" "config.yaml"]))))]
        (is (str/includes? s "- task-1")))))

  (testing "when no tasks are found"
    (with-redefs [config/load! (fn [_] {:tasks []})
                  component/start-system (fn [sys] sys)
                  task/create (fn [t] t)]
      (is (thrown? clojure.lang.ExceptionInfo (core/run-geppetto ["config.yaml"]))))))
