(ns geppetto.system-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            geppetto.errors
            [geppetto.system :as system]
            [geppetto.test-helper :as helper]))

(use-fixtures :each helper/exit-suppressing-fixture)

(def tasks
  [{:name "task-1" :command "command-1" :tags #{"tag-a"}}
   {:name "task-2" :command "command-2" :tags #{"tag-b"}}
   {:name "task-3" :command "command-3" :tags #{"tag-a" "tag-c"}}
   {:name "task-4" :command "command-4"}]) ;; no tags = always runs (like Compose)

(deftest simple-test
  (testing "no filters - should return all tasks"
    (let [sys (system/build {:tasks tasks :tasks-to-launch #{} :tags #{}})]
      (is (= #{:task-1 :task-2 :task-3 :task-4 :watchdog} (set (keys sys)))))))

(deftest filtering-by-name-test

  (testing "filter by name"
    (let [sys (system/build {:tasks tasks :tasks-to-launch #{"task-1"} :tags #{}})]
      (is (= #{:task-1 :watchdog} (set (keys sys)))))))

(deftest filtering-by-tag-test

  (testing "filter by tag - untagged tasks always included"
    (let [sys (system/build {:tasks tasks :tasks-to-launch #{} :tags #{"tag-a"}})]
      (is (= #{:task-1 :task-3 :task-4 :watchdog} (set (keys sys))))))

  (testing "filter by different tag - untagged tasks always included"
    (let [sys (system/build {:tasks tasks :tasks-to-launch #{} :tags #{"tag-b"}})]
      (is (= #{:task-2 :task-4 :watchdog} (set (keys sys)))))))

(deftest filtering-by-name-and-tag-test
  (testing "filter by name and tag"
    (let [sys (system/build {:tasks tasks :tasks-to-launch #{"task-1"} :tags #{"tag-a"}})]
      (is (= #{:task-1 :watchdog} (set (keys sys)))))))

(deftest filtering-by-non-existent-name-or-tag-test
  (testing "filter by non-existent name - name filter overrides all (no tasks match)"
    (is (= [:watchdog] (keys (system/build {:tasks tasks :tasks-to-launch #{"non-existent"} :tags #{}})))))

  (testing "filter by non-existent tag - untagged tasks still included"
    (is (= [:task-4 :watchdog] (keys (system/build {:tasks tasks :tasks-to-launch #{} :tags #{"non-existent"}}))))))
