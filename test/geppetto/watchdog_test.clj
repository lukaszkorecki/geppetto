(ns geppetto.watchdog-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.watchdog :as watchdog]
            [geppetto.task :as task]
            [com.stuartsierra.component :as component]))

(defrecord MockTask [name alive? exit-code]
  task/ITask
  (status [this] {:status (if alive? ::task/alive ::task/clean-exit) :exit-code exit-code})
  (alive? [this] alive?)
  (exit-code [this] exit-code))

(deftest should-exit-test
  (testing "with ::fail-fast mode"
    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" false 1)}]
      (let [exit-info (#'watchdog/should-exit? ::watchdog/fail-fast tasks)]
        (is (= 1 (:exit exit-info)))
        (is (= "Tasks failed: [\"task-2\"]" (:reason exit-info)))))

    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" true 0)}]
      (is (nil? (#'watchdog/should-exit? ::watchdog/fail-fast tasks)))))

  (testing "with ::exit-on-any-completion mode"
    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" false 0)}]
      (let [exit-info (#'watchdog/should-exit? ::watchdog/exit-on-any-completion tasks)]
        (is (= 0 (:exit exit-info)))
        (is (= "Task completed: task-2" (:reason exit-info)))))

    (let [tasks {:task-1 (->MockTask "task-1" true 0)
                 :task-2 (->MockTask "task-2" false 1)}]
      (let [exit-info (#'watchdog/should-exit? ::watchdog/exit-on-any-completion tasks)]
        (is (= 1 (:exit exit-info)))
        (is (= "Task completed: task-2" (:reason exit-info))))))

  (testing "with default exit mode (all tasks completed)"
    (let [tasks {:task-1 (->MockTask "task-1" false 0)
                 :task-2 (->MockTask "task-2" false 0)}]
      (let [exit-info (#'watchdog/should-exit? ::watchdog/keep-going tasks)]
        (is (= 0 (:exit exit-info)))
        (is (= "All tasks completed" (:reason exit-info)))))

    (let [tasks {:task-1 (->MockTask "task-1" false 0)
                 :task-2 (->MockTask "task-2" false 1)}]
      (let [exit-info (#'watchdog/should-exit? ::watchdog/keep-going tasks)]
        (is (= 1 (:exit exit-info)))
        (is (= "All tasks completed" (:reason exit-info)))))))

(deftest watchdog-lifecycle-test
  (testing "starting and stopping the watchdog"
    (let [stop-fn-was-called (atom false)
          watchdog (watchdog/create {:exit-mode ::watchdog/keep-going
                                     :stop-fn (fn [_] (reset! stop-fn-was-called true))})
          started-watchdog (component/start watchdog)]
      (is (:watcher-thread started-watchdog))
      (is (:running? started-watchdog))
      (let [stopped-watchdog (component/stop started-watchdog)]
        (is (nil? (:watcher-thread stopped-watchdog)))
        (is (nil? (:running? stopped-watchdog)))))))
