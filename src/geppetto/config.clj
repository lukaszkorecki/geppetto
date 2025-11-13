(ns geppetto.config
  (:require
   [babashka.fs :as fs]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.error :as me]))

;; Schema & config processing
(def Task
  [:map
   [:command {:min 1} :string]
   [:name {:min 1} :string]
   [:tags {:optional true} [:every [:string {:min 1 :max 32}]]]
   ;; TODO rename `deps` to whatever Docker Compose calls it
   [:deps {:optional true} [:every :string]]
   ;; TODO: readiness-probe - a TCP port to check for readiness
   ;; [:readiness-probe {:optional true} [:map [:port :int :timeout-ms :int]]]
   [:env {:optional true}
    ;; FIXME: we need to also account for numbers
    [:map-of :keyword :string]]

   ;; TODO:
   ;; :env-command {:optional true} [:string]} - command to run to get env vars
   ;; :env-file {:optional true} [:string]} - path to env file to load
   ])

(def TaskConfig
  [:map
   [:settings {:optional true}
  ;; TODO
    [:map-of :string :string]]

   [:tasks
    [:every #'Task]]])

(defn verify-config [conf]
  (when-not (m/validate TaskConfig conf)
    (println "ERROR: invalid config:")
    (-> (m/explain TaskConfig conf)
        me/humanize
        (yaml/generate-string)
        println)
    (System/exit 13)))

(def cl
  (class (yaml/parse-string "foo: bar")))

(defn load! [conf-path]
  (let [conf-path (str (fs/expand-home conf-path))
        _ (when (str/blank? conf-path)
            (println "ERROR: missing config file argument")
            (System/exit 11))
        _ (when-not (fs/exists? conf-path)
            (println "ERROR: config file '%s' does not exist" conf-path)
            (System/exit 12))
        content (slurp conf-path)
        conf (->> (yaml/parse-string content)
                  (walk/postwalk (fn [thing]
                                   (if (instance? cl thing)
                                     (into {} thing)
                                     thing))))]
    (verify-config conf)
    conf))
