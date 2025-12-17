(ns geppetto.errors-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [geppetto.errors :as errors]
            [geppetto.test-helper :as helper]))

(use-fixtures :each helper/exit-suppressing-fixture)

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
    (let [exc (ex-info "test" {:type ::errors/invalid-config :exit-code 2})]
      (helper/assert-exits-with-code 2 (errors/raise! exc)))))
