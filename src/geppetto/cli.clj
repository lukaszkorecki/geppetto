(ns geppetto.cli
  (:require
   [clojure.string :as str]
   [clojure.tools.cli :as cli]
   [geppetto.watchdog :as watchdog]))

(def version "0.0.1")

(def ^:private default-mode ::watchdog/fail-fast)

(def cli-options
  [["-e" "--exit-mode EXIT_MODE" "How to behave when one of tasks exits: fail-fast, exit-on-any-completion, exit-on-all-completion, keep-going"
    :id :exit-mode
    :default default-mode
    :default-desc (name default-mode)
    :parse-fn #(keyword (namespace default-mode) %)
    :validate [watchdog/valid-modes
               (str "Must be one of: " (str/join ", " (map name watchdog/valid-modes)))]]

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

(defn parse-args [cmd-args]
  (let [{:keys [summary errors options arguments] :as _res} (cli/parse-opts cmd-args cli-options)]
    (cond
     (:help options)
     {:exit-code 0
      :message (str/join \newline
                         ["Geppetto - A simple task runner"
                          (str "Version: " version)
                          ""
                          "Usage: geppetto [options] <config-file>"
                          ""
                          "Options:"
                          summary
                          ""])}

     (:print-version options)
     {:exit-code 0
      :message (str "Geppetto version " version)}

     (seq errors)
     {:exit-code 1
      :message (str/join \newline
                         (concat
                          ["Error parsing command line options:"]
                          errors
                          ["" "Usage:" summary]))}

      ;; TODO: support multiple config files
     (not= 1 (count arguments))
     {:exit-code 1
      :message (str/join \newline
                         ["Error: exactly one config file must be specified"
                          ""
                          "Usage:"
                          summary])}

     :else
     (assoc options :config-file (first arguments)))))

(defn process-args [{:keys [exit-code message config-file] :as opts}]
  (when (number? exit-code)
    (println message)
    (System/exit exit-code))

  (let [config-file (cond
                     (str/starts-with? config-file "./")
                     config-file

                     (str/starts-with? config-file "/")
                     config-file

                     :else
                     ;; assume config-file path is relative to cwd
                     (str "./" config-file))]
    (assoc opts :config-file config-file)))
