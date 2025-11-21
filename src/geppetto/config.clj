(ns geppetto.config
  (:refer-clojure :exclude [resolve])
  (:require
   [geppetto.log :as log]
   [geppetto.errors :as errors]
   [babashka.fs :as fs]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.error :as me]))

;; Schema & config processing
(def Task
  [:map
   [:name
    {:description "The unique name of the task"
     :min 1}
    :string]

   [:command
    {:description "The command to run"
     :min 1}
    :string]

   [:tags
    {:description "A list of tags to categorize or group the task"
     :optional true}
    [:every [:string {:min 1 :max 32}]]]

   [:dir
    {:description "Working directory for the process"
     :optional true }
    [:string {:min 1 }]]

   [:depends_on
    {:description "Other task names that this task depends on; must exist in config"
     :optional true}
    [:every :string]]

   [:env
    {:description "Environment variables for the task (as key-value string map)"
     :optional true}
    ;; FIXME: we need to also account for numbers
    [:map-of :keyword :string]]

   [:env_command
    {:description "Shell command to run that provides env vars for the task - usually this would be your secret manager of choice"
     :optional true}
    :string]

   [:env_file
    {:description "Path to a file to load environment variables from for the task. Can be absolute or relative  to :dir if specified, otherwise  relative to config root"
     :optional true}
    :string]])

(def TaskConfig
  [:map
       ;; TODO - configure:
   ;; - output format (text or json)
   ;; - default workdir, to override resolving relative paths based on config file location
   [:settings
    {:optional true :description "Global settings for the task runner"}
    [:map
     [:output_format
      {:description "Output format for task runner logs"
       :optional true}
      [:enum "text" "json"]]

     [:default_workdir
      {:description "Default working directory for tasks with relative paths"
       :optional true
       :min 1}
      :string]]]

   [:tasks
    [:every #'Task]]])

(defn verify! [conf]
  (when-not (m/validate TaskConfig conf)
    (errors/raise! ::errors/invalid-config (fn []
                                             (println "ERROR: invalid config:")
                                             (-> (m/explain TaskConfig conf)
                                                 me/humanize
                                                 (yaml/generate-string)
                                                 println))))
  conf)

(defn- resolve-task-dir [{:keys [dir] :as task} {:keys [config-file-dir]}]
  (printf "%s ->%s \n" dir config-file-dir)
  (cond
    ;; bail out - nothing to do
    (not dir)
    task

    ;; bail out - nothing to do
    (and (not-empty dir) (fs/absolute? dir) (fs/exists? dir))
    task

    ;; we have a dir, it's absolute, but it doesn't exist
    (and (not-empty dir) (fs/absolute? dir) (not (fs/exists? dir)))
    (errors/raise! ::errors/task-dir-doesnt-exist
                   #(println (format "ERROR: task '%s' has a working directory that doesn't exist: %s"
                                     (:name task) dir)))

    ;; we have a dir, it's relative - resolve it
    (and (not-empty dir) (not (fs/absolute? dir)))
    (let [final-path (-> (str config-file-dir "/" dir)
                         fs/absolutize
                         fs/normalize)]
      (if (fs/exists? final-path)
        (assoc task :dir (str final-path))
        (errors/raise! ::errors/task-dir-doesnt-exist
                       #(println (format "ERROR: task '%s' has a working directory that doesn't exist: %s"
                                         (:name task) final-path)))))))

(defn parse-env-file [env-file-path]
  (->> (env-file-path)
       slurp
       str/split-lines
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (map (fn [line]
              (let [[k v] (str/split (str/replace line #"export\s+" "") #"=" 2)]
                [k v])))
       (into {})))

(defn resolve-env [task {:keys [config-file-dir]}]
  (if-let [env-file (:env_file task)]
    (let [resolved-path (if (fs/absolute? env-file)
                          env-file
                          (str (fs/absolutize (fs/normalize (str config-file-dir "/" env-file)))))]
      (if (fs/exists? resolved-path)
        (update task :env merge (parse-env-file resolved-path))
        (errors/raise! ::errors/task-env-file-doesnt-exist
                       #(println (format "ERROR: task '%s' has an env_file that doesn't exist: %s"
                                         (:name task) resolved-path)))))
    task))

(defn resolve
  "Figures out other things post-structure validation:
  - resolves workdirs so that they're absolute path, with the config location being the root in case of relative paths
  - ensures that `depends_on` references existing tasks
"
  [conf {:keys [config-file-dir]}]
  (-> conf
      (update :tasks (fn [tasks]
                       (->> tasks
                            (mapv (fn [task]
                                    (-> task
                                        (resolve-task-dir {:config-file-dir config-file-dir})
                                        (resolve-env {:config-file-dir config-file-dir})))))))))

;; FIXME: why do we need to do this?
(def ordered-map-class
  (class (yaml/parse-string "foo: bar")))

(defn load! [conf-path]
  (when (str/blank? conf-path)
    (errors/raise! ::errors/config-not-found))
  (let [conf-path (fs/expand-home conf-path)
        _ (when-not (fs/exists? conf-path)
            (errors/raise! ::errors/config-not-found))
        ;; we're good to go
        config-file-dir (str (fs/normalize (fs/absolutize (fs/parent conf-path))))
        conf-data (->> (yaml/parse-string (slurp (str conf-path)))
                       ;; convert ordered-map to regular maps, they're easier to work with
                       (walk/postwalk (fn [thing]
                                        (if (instance? ordered-map-class thing)
                                          (into {} thing)
                                          thing))))]
    (-> conf-data
        verify!
        (resolve {:config-file-dir config-file-dir}))))
