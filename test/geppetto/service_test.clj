(ns geppetto.service-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.service :as service]
            [com.stuartsierra.component :as component]))

(deftest service-lifecycle-test
  (testing "a service that runs and exits successfully"
    (let [service-def {:name "test-service" :command "echo hello"}
          svc (service/create service-def)
          started-service (component/start svc)]
      (Thread/sleep 1000) ; give it time to finish
      (is (= {:status ::service/clean-exit :exit-code 0} (service/status started-service)))
      (is (= false (service/alive? started-service)))
      (is (= 0 (service/exit-code started-service)))
      (let [stopped-service (component/stop started-service)]
        (is (nil? (:process stopped-service))))))

  (testing "stopping a running service"
    (let [service-def {:name "long-running-service" :command "sleep 5"}
          svc (service/create service-def)
          started-service (component/start svc)]
      (is (service/alive? started-service))
      (let [stopped-service (component/stop started-service)]
        (is (not (service/alive? stopped-service)))
        (is (not= 0 (service/exit-code stopped-service)))))))
