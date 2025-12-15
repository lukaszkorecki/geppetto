(ns geppetto.core
  (:gen-class)
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [com.stuartsierra.component :as component]
   [geppetto.cli :as cli]
   [geppetto.config :as config]
   [geppetto.logger :as logger]
   [geppetto.task :as task]
   [geppetto.watchdog :as watchdog]
   [mokujin.log :as log]))

(set! *warn-on-reflection* true)

;; TODO: move to separate ns
(defn- build-system [{:keys [tasks exit-mode tasks-to-launch tags] :as _args}]
  {:pre [(set? tasks-to-launch)
         (set? tags)
         (not-empty tasks)]}
  ;; verify that task filter mentions tasks that actually are defined in config
  (when (and (seq tasks-to-launch)
             (nil? (seq (set/intersection
                         (set tasks-to-launch)
                         (set (map :name tasks))))))
    (log/error "No matching tasks found to launch. Exiting.")
    (System/exit 1))
  (let [longest-name-char-count (apply max (map #(count (:name %)) tasks))
        task-sys (->> tasks
                      ;; first filter by tags
                      #_(filter (fn [task]
                                  (or ;; untagged tasks always launch
                                   (empty? (:tags task))
                                   ;; when we have tag filters, check for intersection
                                   (and (seq tags) (seq (set/intersection tags (:tags task)))))))

                      ;; then filter by name
                      (filter (fn [{:keys [name] :as _task}]
                                (or
                                 ;; no name filter means launch all
                                 (empty? tasks-to-launch)

                                 ;; otherwise only launch if in the set
                                 (contains? tasks-to-launch name))))

                      (map (fn [{:keys [name depends_on] :as task-def}]
                             (let [;; loggable name padded to longest task name for prettier logs
                                   loggable-name (str (logger/colorize name)
                                                     (apply str (repeat
                                                                 (- longest-name-char-count
                                                                    (count name))
                                                                 " ")))
                                   task (task/create (assoc task-def :loggable-name loggable-name))
                                   dependencies (mapv keyword (seq depends_on))
                                   task (component/using
                                         task
                                         (vec (concat [] dependencies)))]
                               (hash-map (keyword name) task))))
                      (into {}))

        task-sys (assoc task-sys :watchdog (component/using
                                            (watchdog/create {:exit-mode exit-mode
                                                              :stop-fn (fn [{:keys [exit]}]
                                                                         (Thread/sleep 300) ;; allow logs to flush
                                                                         (shutdown-agents)
                                                                         (System/exit exit))})

                                            (mapv keyword (keys task-sys))))]

    (component/map->SystemMap task-sys)))

(def sys (atom nil))

;; pre-bake logger init to capture any early logs + set up the logging system during compile time
(logger/init! {:debug? (not-empty (System/getenv "DEBUG"))})

(defn -main [& args]
  (logger/init! {:debug? (not-empty (System/getenv "DEBUG"))})
  (log/with-context {:task "geppetto"}
    (let [{:keys [config-file tasks-to-launch exit-mode debug print-tasks tags]} (cli/process-args (cli/parse-args args))
          {:keys [tasks _settings] :as _conf} (config/load! config-file)
          ;; FIXME: we can improve all of this much better:
          ;; - merge CLI and config file data into one settings map and pass that around
          ;; - move to multimethods for hadnling different CLI options (print tasks, validate config, etc)
          ;; - move `build-system` to separate ns
          sys-map (build-system {:tasks tasks
                                 :tasks-to-launch tasks-to-launch
                                 :tags tags
                                 :exit-mode exit-mode})
          ;; we 'dec' because watchdog is also part of the system map
          task-count (dec (count sys-map))]

      (when (zero? task-count)
        (log/with-context {:event "invalid options"}
          (log/error "No tasks to start. Exiting."))
        (System/exit 1))

      (when print-tasks
        (println "Defined tasks")
        (->> (keys sys-map)
             (remove #(= :watchdog %))
             (mapv #(str "- " (name %)))
             sort
             (str/join "\n")
             println)

        (System/exit 0))

      (log/with-context {:event "START" :task "geppetto"}
        (log/infof "Starting with config %s - %s tasks\n" config-file task-count))

      (logger/init! {:debug? (or (not-empty (System/getenv "DEBUG")) debug)})
      (reset! sys (component/start-system sys-map))
      (while true
        (Thread/sleep 1000)))))
