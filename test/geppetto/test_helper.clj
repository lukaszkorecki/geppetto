(ns geppetto.test-helper
  (:require [geppetto.exit :as exit]
            [clojure.test :refer [is]]))

(defn exit-suppressing-fixture [f]
  (binding [exit/*exit-fn* (fn [code] (throw (ex-info "System/exit called" {:code code})))]
    (f)))

(defmacro assert-exits-with-code [code & body]
  `(try
     ~@body
     (is false "Expected System/exit to be called")
     (catch clojure.lang.ExceptionInfo e#
       (is (= ~code (:code (ex-data e#)))))))
