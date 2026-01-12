(ns geppetto.core
  (:gen-class)
  (:require
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [com.stuartsierra.component :as component]
   [geppetto.config :as config]
   [geppetto.errors :as errors]
   [geppetto.exit :as exit]
   [geppetto.logger :as logger]
   [geppetto.system :as system]
   [geppetto.watchdog :as watchdog]
   [mokujin.log :as log]))

(set! *warn-on-reflection* true)

;; pre-bake logger init to capture any early logs + set up the logging system during compile time
;; NOTE: DEBUG flag only has effect in JVM version, since native-image compilation will inline the value of the env var and disable debug logging if not set during build time!
(logger/init! {:debug? (not-empty (System/getenv "DEBUG"))})

(def version "0.0.1")

(def ^:private default-mode ::watchdog/all)

(def cli-options
  [["-e" "--exit-mode EXIT_MODE"
    (str "Exit behavior when tasks complete or fail:\n"
         (str/join "\n" (map (fn [[mode desc]]
                               (str "                                 - " (name mode) ": " desc))
                             (sort-by (comp name first) watchdog/valid-modes))))
    :id :exit-mode
    :default default-mode
    :default-desc (name default-mode)
    :parse-fn #(keyword (namespace default-mode) %)
    :validate [(set (keys watchdog/valid-modes))
               (str "Must be one of: " (str/join ", " (map name (keys watchdog/valid-modes))))]]

   ["-t" "--tasks TASKS" "Comma separated list of tasks to run (default: all tasks in config)"
    :id :tasks-to-launch
    :parse-fn #(set (str/split % #","))
    :default #{}]

   ["-T" "--tags TAGS" "Comma separated list of tags to filter tasks to run (default: all tasks in config)"
    :id :tags
    :parse-fn #(set (str/split % #","))
    :default #{}]

   ["-h" "--help" "Show help"
    :id :help]

   ["-v" "--version" "Show version"
    :id :print-version]

   ["-p" "--print-tasks" "Print the list of tasks defined in the config file and exit"
    :id :print-tasks]

   [nil "--debug" "Enable debug logging. Can be also enabled by setting DEBUG env var to non-empty value."
    :id :debug]])

(defmulti cli-dispatch (fn [{:keys [action] :as _args}] action))

(defmethod cli-dispatch :default [_]
  (errors/raise! ::errors/unknown-cli-command))

(defmethod cli-dispatch :print-version [_]
  (println (str "Geppetto version " version))
  (exit/exit! 0))

(defmethod cli-dispatch :help [{:keys [summary errors] :as _args}]
  (let [help-text (str/join \newline
                            ["Geppetto - A simple task runner"
                             (str "Version: " version)
                             ""
                             "Usage: geppetto [options] <config-file>"
                             ""
                             "Options:"
                             summary
                             ""])]

    (println help-text)
    (when (seq errors)
      (println (str/join \newline errors))
      (exit/exit! 13))
    (exit/exit! 0)))

(defmethod cli-dispatch :invalid-options [{:keys [summary errors]}]
  (cli-dispatch {:action :help
                 :summary summary
                 :errors errors}))

(defmethod cli-dispatch :print-tasks [{:keys [system context] :as _args}]
  (log/with-context {:task "geppetto"}
    (log/info "Tasks defined in config:")
    (doseq [task (-> context :tasks)]
      (println (str "- " (:name task)))
      (when-let [task-tags (seq (:tags task))]
        (println (str "    Tags: " (str/join ", " (sort task-tags)))))

      (when-let [deps (:depends_on task)]
        (println (str "    Depends on: " (str/join ", " (sort deps))))))

    (when (or (seq (:tasks-to-launch context))
              (seq (:tags context)))
      (log/info "Effective filters applied:")
      (log/infof "Active filters: %s %s"
                 (if (empty? (:tasks-to-launch context))
                   "tasks=none"
                   (str "tasks=" (str/join ", " (sort (:tasks-to-launch context)))))
                 (if (empty? (:tags context))
                   "tags=none"
                   (str "tags=" (str/join ", " (sort (:tags context))))))

      (doseq [task-name (->> system
                             keys
                             (remove #{:watchdog})
                             sort)]

        (println (str "- " (name task-name)))
        (when-let [task-tags (seq (:tags (get system task-name)))]
          (println (str "    Tags: " (str/join ", " (sort task-tags)))))))
    (exit/exit! 0)))

(defmethod cli-dispatch :start [{:keys [system context] :as _args}]
  (log/with-context {:task "geppetto"}
    (let [task-count (dec (count system))] ;; subtract watchdog

      (log/with-context {:event "START" :task "geppetto"}
        (log/infof "Starting with config %s - %s tasks\n" (:config-file context) task-count))

      ;; Start the system
      (let [started-system (component/start-system system)
            ;; Extract the watchdog and wait for it to signal exit
            wd (:watchdog started-system)
            {:keys [exit reason] :as _exit-info} (watchdog/wait-for-exit wd)]

        (log/with-context {:task "geppetto"}
          (log/infof "Shutdown requested: %s" reason))

        ;; Graceful shutdown: stop all components
        (log/with-context {:task "geppetto"}
          (log/info "Stopping system components..."))

        (component/stop-system started-system)

        ;; Allow logs to flush
        (Thread/sleep 300)
        (shutdown-agents)

        ;; Exit with the code from the shutdown signal
        (exit/exit! exit)))))

;; Handle command line arguments and return a map with action and relevant data
(defn handle-args [cmd-args]
  (let [{:keys [summary errors options arguments] :as _res} (cli/parse-opts cmd-args cli-options)]
    (cond
      ;; early exits that don't need context/config
      (:help options) {:action :help
                       :summary summary}

      (seq errors) {:action :invalid-options
                    :summary summary
                    :errors errors}

      (:print-version options) {:action :print-version}

      ;; we're getting to the good part now
      :else (if-let [config-file (first arguments)]
              (let [config-file-path (cond
                                       (str/starts-with? config-file "./")
                                       config-file

                                       (str/starts-with? config-file "/")
                                       config-file

                                       :else
                                       ;; assume config-file path is relative to cwd
                                       (str "./" config-file))
                    {:keys [print-tasks tags exit-mode tasks-to-launch]} options
                    {:keys [tasks _settings] :as _conf} (config/load! config-file-path)

                    _ (when (empty? tasks)
                        (errors/raise! ::errors/no-tasks-in-config))

                    context {:tasks tasks
                             :config-file config-file-path
                             :tasks-to-launch tasks-to-launch
                             :tags tags
                             :exit-mode exit-mode}

                    system (system/build context)]

                (if print-tasks
                  {:action :print-tasks
                   :system system
                   :context context}

                  {:action :start
                   :context context
                   :system system}))

              {:action :invalid-options
               :summary summary
               :errors ["No config file specified."]}))))

(defn -main [& args]
  (let [something' (handle-args (vec args))]
    (cli-dispatch something')))
