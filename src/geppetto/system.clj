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
    ;; - tasks WITH tags only run when their profile/tag is explicitly enabled
    ;; - name filtering overrides tag filtering: explicitly named tasks always run
    (let [final-tasks-list (->> tasks
                                ;; first filter by name (takes priority)
                                (filter (fn [{:keys [name] :as _task}]
                                          (or
                                           ;; no name filter means apply tag filtering
                                           (empty? tasks-to-launch)
                                           ;; name filter active: include if in the set (bypasses tag check)
                                           (contains? tasks-to-launch name))))

                                ;; then filter by tags (only if name filter wasn't applied)
                                (filter (fn [{:keys [name] :as task}]
                                          (or
                                           ;; explicitly named task bypasses tag filtering
                                           (contains? tasks-to-launch name)
                                           ;; no tags in task -> always include
                                           (empty? (:tags task))
                                           ;; task has tags -> only include if one of its tags is active
                                           (and (seq (:tags task))
                                                (seq (set/intersection tags (:tags task))))))))

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
