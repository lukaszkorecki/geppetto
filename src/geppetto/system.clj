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
    {}
    (do
      ;; verify that task filter mentions tasks that actually are defined in config
      (when (and (seq tasks-to-launch)
                 (empty? (set/intersection
                          (set tasks-to-launch)
                          (set (map :name tasks)))))
        (errors/raise! ::errors/no-matching-tasks))
      (let [longest-name-char-count (apply max (map #(count (:name %)) tasks))
            task-sys (->> tasks
                          ;; first filter by tags
                          (filter (fn [task]
                                    (if (empty? tags)
                                      true
                                      (or
                                       (empty? (:tags task))
                                       (seq (set/intersection tags (:tags task)))))))

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
                                                                             (exit/exit! exit))})

                                                (mapv keyword (keys task-sys))))]

        (component/map->SystemMap task-sys)))))
