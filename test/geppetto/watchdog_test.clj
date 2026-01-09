(ns geppetto.watchdog-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.watchdog :as watchdog]
            [geppetto.task :as task]
            [com.stuartsierra.component :as component]))

(defrecord MockTask [name alive? exit-code]
  task/ITask
  (status [_this] {:status (if alive? ::task/alive ::task/clean-exit) :exit-code exit-code})
  (alive? [_this] alive?)
  (exit-code [_this] exit-code))

(deftest should-exit-test
  (testing "with ::on-failure mode - exits on first failure"
    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" false 1)}
          exit-info (#'watchdog/should-exit? ::watchdog/on-failure tasks)]
      (is (= 1 (:exit exit-info)))
      (is (= "Tasks failed: [\"task-2\"]" (:reason exit-info))))

    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" true 0)}]
      (is (nil? (#'watchdog/should-exit? ::watchdog/on-failure tasks)))))

  (testing "with ::any mode - exits when any task completes"
    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" false 0)}
          exit-info (#'watchdog/should-exit? ::watchdog/any tasks)]
      (is (= 0 (:exit exit-info)))
      (is (= "Task completed: task-2" (:reason exit-info))))

    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" false 1)}
          exit-info (#'watchdog/should-exit? ::watchdog/any tasks)]
      (is (= 1 (:exit exit-info)))
      (is (= "Task completed: task-2" (:reason exit-info)))))

  (testing "with ::all mode - waits for all tasks to complete"
    (let [tasks {:task-1 (->MockTask "task-1" false 0)
                 :task-2 (->MockTask "task-2" false 0)}
          exit-info (#'watchdog/should-exit? ::watchdog/all tasks)]
      (is (= 0 (:exit exit-info)))
      (is (= "All tasks completed" (:reason exit-info))))

    (let [tasks {:task-1 (->MockTask "task-1" false 0)
                 :task-2 (->MockTask "task-2" false 1)}
          exit-info (#'watchdog/should-exit? ::watchdog/all tasks)]
      (is (= 1 (:exit exit-info)))
      (is (= "All tasks completed" (:reason exit-info))))))

(deftest watchdog-lifecycle-test
  (testing "starting and stopping the watchdog"
    (let [watchdog (watchdog/create {:exit-mode ::watchdog/all})
          started-watchdog (component/start watchdog)]
      (is (:watcher-thread started-watchdog))
      (is (:running? started-watchdog))
      (is (:shutdown-promise started-watchdog))
      (let [stopped-watchdog (component/stop started-watchdog)]
        (is (nil? (:watcher-thread stopped-watchdog)))
        (is (nil? (:running? stopped-watchdog)))))))
