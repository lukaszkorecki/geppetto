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

(def tasks-with-deps
  [{:name "foobar" :command "echo foobar"}
   {:name "barbaz" :command "echo barbaz" :depends_on ["foobar"]}
   {:name "quxbaz" :command "echo quxbaz" :depends_on ["barbaz"]}])

(def tasks-with-deps-and-tags
  [{:name "db" :command "echo db"}
   {:name "api" :command "echo api" :depends_on ["db"] :tags #{"backend"}}
   {:name "worker" :command "echo worker" :depends_on ["db"] :tags #{"backend"}}
   {:name "frontend" :command "echo frontend" :depends_on ["api"] :tags #{"frontend"}}
   {:name "admin" :command "echo admin" :depends_on ["api"] :tags #{"admin"}}])

(deftest simple-test
  (testing "no filters - should return only untagged tasks (Docker Compose behavior)"
    (let [sys (system/build {:tasks tasks :tasks-to-launch #{} :tags #{}})]
      (is (= #{:task-4 :watchdog} (set (keys sys)))))))

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

(deftest dependency-resolution-test
  (testing "selecting a task includes its dependencies"
    (let [sys (system/build {:tasks tasks-with-deps :tasks-to-launch #{"barbaz"} :tags #{}})]
      (is (= #{:foobar :barbaz :watchdog} (set (keys sys))))
      (is (contains? sys :foobar))
      (is (contains? sys :barbaz))))

  (testing "selecting a task with transitive dependencies includes all"
    (let [sys (system/build {:tasks tasks-with-deps :tasks-to-launch #{"quxbaz"} :tags #{}})]
      (is (= #{:foobar :barbaz :quxbaz :watchdog} (set (keys sys))))
      (is (contains? sys :foobar))
      (is (contains? sys :barbaz))
      (is (contains? sys :quxbaz))))

  (testing "selecting multiple tasks includes their combined dependencies"
    (let [sys (system/build {:tasks tasks-with-deps :tasks-to-launch #{"barbaz" "quxbaz"} :tags #{}})]
      (is (= #{:foobar :barbaz :quxbaz :watchdog} (set (keys sys))))))

  (testing "selecting a leaf task only includes its dependencies, not siblings"
    (let [sys (system/build {:tasks tasks-with-deps :tasks-to-launch #{"barbaz"} :tags #{}})]
      (is (not (contains? sys :quxbaz))))))

(deftest dependency-resolution-with-tags-test
  (testing "tag filter includes dependencies even if dependency has no matching tag"
    ;; When we select by tag "backend", we get api and worker
    ;; Both depend on db, so db should be included even though it has no tags
    (let [sys (system/build {:tasks tasks-with-deps-and-tags :tasks-to-launch #{} :tags #{"backend"}})]
      (is (= #{:db :api :worker :watchdog} (set (keys sys))))
      (is (contains? sys :db))
      (is (contains? sys :api))
      (is (contains? sys :worker))
      (is (not (contains? sys :frontend)))
      (is (not (contains? sys :admin)))))

  (testing "tag filter with transitive dependencies includes all deps in chain"
    ;; frontend depends on api, which depends on db
    ;; All three should be included when filtering by "frontend" tag
    (let [sys (system/build {:tasks tasks-with-deps-and-tags :tasks-to-launch #{} :tags #{"frontend"}})]
      (is (= #{:db :api :frontend :watchdog} (set (keys sys))))
      (is (contains? sys :db))
      (is (contains? sys :api))
      (is (contains? sys :frontend))
      (is (not (contains? sys :worker)))
      (is (not (contains? sys :admin)))))

  (testing "multiple tag filter includes all matching tasks and their combined dependencies"
    ;; backend tags: api, worker (both depend on db)
    ;; frontend tag: frontend (depends on api, which depends on db)
    ;; Should get: db, api, worker, frontend
    (let [sys (system/build {:tasks tasks-with-deps-and-tags :tasks-to-launch #{} :tags #{"backend" "frontend"}})]
      (is (= #{:db :api :worker :frontend :watchdog} (set (keys sys))))
      (is (not (contains? sys :admin)))))

  (testing "multiple tag filter with all tags includes everything"
    (let [sys (system/build {:tasks tasks-with-deps-and-tags :tasks-to-launch #{} :tags #{"backend" "frontend" "admin"}})]
      (is (= #{:db :api :worker :frontend :admin :watchdog} (set (keys sys)))))))
