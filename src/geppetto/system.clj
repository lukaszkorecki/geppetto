(ns geppetto.system
  (:require
   [clojure.set :as set]
   [com.stuartsierra.component :as component]
   [geppetto.errors :as errors]
   [geppetto.exit :as exit]
   [geppetto.logger :as logger]
   [geppetto.task :as task]
   [geppetto.watchdog :as watchdog]))

(defn build [{:keys [tasks exit-mode tasks-to-launch tags] :as _args}]
  {:pre [(set? tasks-to-launch)
         (set? tags)
         (vector? tasks)]}
  (if (empty? tasks)
    (errors/raise! ::errors/no-tasks-in-config)

    ;; filtering rules (Docker Compose compatible):
    ;; - tasks WITHOUT tags always run (like Compose services without profiles)
    ;; - tasks WITH tags only run when their tag matches the filter
    ;; - name filtering overrides everything: only explicitly named tasks run
    (let [final-tasks-list (->> tasks
                                ;; first filter by tags
                                (filter (fn [task]
                                          (or
                                           ;; no tags in task -> always include (like Compose services without profiles)
                                           (empty? (:tags task))
                                           ;; no tag filter specified -> include all tasks
                                           (empty? tags)
                                           ;; tag filter active -> only include if task has matching tags
                                           (seq (set/intersection tags (:tags task))))))

                                ;; then filter by name
                                (filter (fn [{:keys [name] :as _task}]
                                          (or
                                           ;; no name filter means launch all
                                           (empty? tasks-to-launch)

                                           ;; otherwise only launch if in the set
                                           (contains? tasks-to-launch name)))))

          longest-name-char-count (when (seq final-tasks-list)
                                    (apply max (map #(count (:name %)) final-tasks-list)))
          task-sys (->> final-tasks-list
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
                                                                           (exit/exit! exit))})

                                              (mapv keyword (keys task-sys))))]
      (component/map->SystemMap task-sys))))
