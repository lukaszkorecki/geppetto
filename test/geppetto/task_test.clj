(ns geppetto.task-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.task :as task]
            [com.stuartsierra.component :as component]))

(deftest task-lifecycle-test
  (testing "a task that runs and exits successfully"
    (let [task-def {:name "test-task" :command "echo hello"}
          task (task/create task-def)
          started-task (component/start task)]
      (Thread/sleep 1000) ; give it time to finish
      (is (= {:status ::task/clean-exit :exit-code 0} (task/status started-task)))
      (is (= false (task/alive? started-task)))
      (is (= 0 (task/exit-code started-task)))
      (let [stopped-task (component/stop started-task)]
        (is (nil? (:process stopped-task))))))

  (testing "stopping a running task"
    (let [task-def {:name "long-running-task" :command "sleep 5"}
          task (task/create task-def)
          started-task (component/start task)]
      (is (task/alive? started-task))
      (let [stopped-task (component/stop started-task)]
        (is (not (task/alive? stopped-task)))
        (is (not= 0 (task/exit-code stopped-task)))))))
