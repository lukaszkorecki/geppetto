(ns geppetto.watchdog-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.watchdog :as watchdog]
            [geppetto.service :as service]
            [com.stuartsierra.component :as component]))

(defrecord MockService [name alive? exit-code]
  service/IService
  (status [_this] {:status (if alive? ::service/alive ::service/clean-exit) :exit-code exit-code})
  (alive? [_this] alive?)
  (exit-code [_this] exit-code))

(deftest should-exit-test
  (testing "with ::on-failure mode - exits on first failure"
    (let [services {:svc-1 (->MockService "svc-1" true 0)
                    :svc-2 (->MockService "svc-2" false 1)}
          exit-info (#'watchdog/should-exit? ::watchdog/on-failure services)]
      (is (= 1 (:exit exit-info)))
      (is (= "Services failed: [\"svc-2\"]" (:reason exit-info))))

    (let [services {:svc-1 (->MockService "svc-1" true 0)
                    :svc-2 (->MockService "svc-2" true 0)}]
      (is (nil? (#'watchdog/should-exit? ::watchdog/on-failure services)))))

  (testing "with ::any mode - exits when any service completes"
    (let [services {:svc-1 (->MockService "svc-1" true 0)
                    :svc-2 (->MockService "svc-2" false 0)}
          exit-info (#'watchdog/should-exit? ::watchdog/any services)]
      (is (= 0 (:exit exit-info)))
      (is (= "Service completed: svc-2" (:reason exit-info))))

    (let [services {:svc-1 (->MockService "svc-1" true 0)
                    :svc-2 (->MockService "svc-2" false 1)}
          exit-info (#'watchdog/should-exit? ::watchdog/any services)]
      (is (= 1 (:exit exit-info)))
      (is (= "Service completed: svc-2" (:reason exit-info)))))

  (testing "with ::all mode - waits for all services to complete"
    (let [services {:svc-1 (->MockService "svc-1" false 0)
                    :svc-2 (->MockService "svc-2" false 0)}
          exit-info (#'watchdog/should-exit? ::watchdog/all services)]
      (is (= 0 (:exit exit-info)))
      (is (= "All services completed" (:reason exit-info))))

    (let [services {:svc-1 (->MockService "svc-1" false 0)
                    :svc-2 (->MockService "svc-2" false 1)}
          exit-info (#'watchdog/should-exit? ::watchdog/all services)]
      (is (= 1 (:exit exit-info)))
      (is (= "All services completed" (:reason exit-info))))))

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
