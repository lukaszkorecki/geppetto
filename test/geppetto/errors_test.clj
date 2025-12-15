(ns geppetto.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.errors :as errors]))

(deftest type->exc-test
  (testing "with unknown type"
    (let [exc (errors/type->exc :unknown-type)]
      (is (= :unknown-type (-> exc ex-data :type))))))

(deftest throw-or-exit-test
  (testing "with non-fatal error"
    (let [exc (ex-info "test" {:type :test})]
      (is (thrown? clojure.lang.ExceptionInfo (errors/throw-or-exit exc))))))

(deftest raise-test
  (testing "with pre-existing exception"
    (binding [errors/*really-exit?* false]
      (let [exc (ex-info "test" {:type ::errors/invalid-config :exit-code 2})]
        (is (thrown? clojure.lang.ExceptionInfo (errors/raise! exc)))))))
