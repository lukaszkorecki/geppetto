(ns geppetto.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.core :as core]
            [geppetto.errors :as errors]))

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
    (binding [errors/*really-exit?* false]
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'core/build-system {:tasks tasks :tasks-to-launch #{"non-existent"} :tags #{}})))))

  (testing "filter by non-existent tag"
    (let [sys (#'core/build-system {:tasks tasks :tasks-to-launch #{} :tags #{"non-existent"}})]
      (is (= #{:task-4 :watchdog} (set (keys sys)))))))
