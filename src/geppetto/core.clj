(ns geppetto.core
  (:gen-class)
  (:require
   [clojure.java.io :as io]
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

(def version
  (or (some-> (io/resource "VERSION") slurp str/trim)
      "0.0.0-dev"))

(def ^:private default-mode ::watchdog/all)

(def cli-options
  [["-e" "--exit-mode EXIT_MODE"
    (str "Exit behavior when services complete or fail (default: " (name default-mode) "):\n"
         (str/join "\n" (map (fn [[mode desc]]
                               (str "                                 - " (name mode) ": " desc))
                             (sort-by (comp name first) watchdog/valid-modes))))
    :id :exit-mode
    :parse-fn #(keyword (namespace default-mode) %)
    :validate [(set (keys watchdog/valid-modes))
               (str "Must be one of: " (str/join ", " (map name (keys watchdog/valid-modes))))]]

   ["-s" "--services SERVICES" "Comma separated list of services to run (default: all services in config)"
    :id :services-to-launch
    :parse-fn #(set (str/split % #","))
    :default #{}]

   ["-t" "--tags TAGS" "Comma separated list of tags to filter services to run (default: all services in config)"
    :id :tags
    :parse-fn #(set (str/split % #","))
    :default #{}]

   ["-h" "--help" "Show help"
    :id :help]

   ["-v" "--version" "Show version"
    :id :print-version]

   ["-p" "--print-services" "Print the list of services defined in the config file and exit"
    :id :print-services]

   ["-r" "--root ROOT" "Root directory for resolving relative service paths (default: config file directory)"
    :id :root-dir]])

(defmulti cli-dispatch (fn [{:keys [action] :as _args}] action))

(defmethod cli-dispatch :default [_]
  (errors/raise! ::errors/unknown-cli-command))

(defmethod cli-dispatch :print-version [_]
  (println (str "Geppetto version " version))
  (exit/exit! 0))

(defmethod cli-dispatch :help [{:keys [summary errors] :as _args}]
  (let [help-text (str/join \newline
                            ["Geppetto - A simple service runner"
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

(defmethod cli-dispatch :print-services [{:keys [system context] :as _args}]
  (log/with-context {:service "geppetto"}
    (log/info "Services defined in config:")
    (doseq [svc (-> context :services)]
      (println (str "- " (:name svc)))
      (when-let [svc-tags (seq (:tags svc))]
        (println (str "    Tags: " (str/join ", " (sort svc-tags)))))

      (when-let [deps (:depends_on svc)]
        (println (str "    Depends on: " (str/join ", " (sort deps))))))

    (when (or (seq (:services-to-launch context))
              (seq (:tags context)))
      (log/info "Effective filters applied:")
      (log/infof "Active filters: %s %s"
                 (if (empty? (:services-to-launch context))
                   "services=none"
                   (str "services=" (str/join ", " (sort (:services-to-launch context)))))
                 (if (empty? (:tags context))
                   "tags=none"
                   (str "tags=" (str/join ", " (sort (:tags context))))))

      (doseq [svc-name (->> system
                            keys
                            (remove #{:watchdog})
                            sort)]

        (println (str "- " (name svc-name)))
        (when-let [svc-tags (seq (:tags (get system svc-name)))]
          (println (str "    Tags: " (str/join ", " (sort svc-tags)))))))
    (exit/exit! 0)))

(defmethod cli-dispatch :start [{:keys [system context] :as _args}]
  (log/with-context {:service "geppetto"}
    (let [service-count (dec (count system))] ;; subtract watchdog

      (log/with-context {:event "START" :service "geppetto"}
        (log/infof "Starting with config %s - %s services\n" (:config-file context) service-count))

      ;; Start the system
      (let [{:keys [watchdog] :as started-system} (component/start-system system)
            {:keys [exit reason] :as _exit-info} (watchdog/wait-for-exit watchdog)]
        (log/with-context {:service "geppetto"}
          (log/infof "Shutdown requested: %s" reason))

        ;; Graceful shutdown: stop all components
        (log/with-context {:service "geppetto"}
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
                    {:keys [print-services tags exit-mode services-to-launch root-dir]} options
                    {:keys [services settings]} (config/load! config-file-path {:root-dir root-dir})

                    _ (when (empty? services)
                        (errors/raise! ::errors/no-services-in-config))

                    ;; Priority: CLI > config > default
                    effective-exit-mode (or exit-mode
                                            (some->> (:exit_mode settings)
                                                     (keyword (namespace default-mode)))
                                            default-mode)

                    context {:services services
                             :config-file config-file-path
                             :services-to-launch services-to-launch
                             :tags tags
                             :exit-mode effective-exit-mode}

                    system (system/build context)]

                (if print-services
                  {:action :print-services
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
