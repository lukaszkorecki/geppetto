(ns geppetto.logger-test
  (:require [clojure.test :refer [deftest is testing]]
            [geppetto.logger :as logger]
            [clojure.string :as str]))

(deftest format-line-test
  (testing "with :parse-json? true and valid JSON"
    (let [result (logger/format-line "{\"level\":\"info\",\"msg\":\"hello\"}" {:parse-json? true})]
      (is (str/includes? result "level: info"))
      (is (str/includes? result "msg: hello"))))

  (testing "with :parse-json? true and non-JSON"
    (is (= "plain text" (logger/format-line "plain text" {:parse-json? true}))))

  (testing "with :parse-json? false, passes through unchanged"
    (is (= "{\"level\":\"info\"}" (logger/format-line "{\"level\":\"info\"}" {:parse-json? false}))))

  (testing "with :parse-json? nil, passes through unchanged"
    (is (= "{\"level\":\"info\"}" (logger/format-line "{\"level\":\"info\"}" {:parse-json? nil}))))

  (testing "with empty opts map, passes through unchanged"
    (is (= "{\"level\":\"info\"}" (logger/format-line "{\"level\":\"info\"}" {}))))

  (testing "JSON arrays are not parsed (only maps)"
    (is (= "[1,2,3]" (logger/format-line "[1,2,3]" {:parse-json? true}))))

  (testing "malformed JSON passes through unchanged"
    (is (= "{invalid json" (logger/format-line "{invalid json" {:parse-json? true})))))
