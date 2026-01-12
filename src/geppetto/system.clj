(ns geppetto.system
  (:require
   [clojure.set :as set]
   [com.stuartsierra.component :as component]
   [com.stuartsierra.dependency :as dep]
   [geppetto.errors :as errors]
   [geppetto.logger :as logger]
   [geppetto.task :as task]
   [geppetto.watchdog :as watchdog]))

(defn- resolve-dependencies
  "Given a list of tasks and a set of selected task names, returns a set of all task names
  including transitive dependencies. Uses Component's dependency library to resolve the graph."
  [all-tasks selected-names]
  ;; Build dependency graph from all tasks
  (let [dep-graph (reduce (fn [graph {:keys [name depends_on]}]
                            (reduce (fn [g' dep]
                                      (dep/depend g' name dep))
                                    graph
                                    depends_on))
                          (dep/graph)
                          all-tasks)
        ;; Get transitive dependencies (doesn't include the selected names themselves)
        dependencies (dep/transitive-dependencies-set dep-graph selected-names)]
    ;; Return both selected names and their dependencies
    (set/union selected-names dependencies)))

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
    (let [initially-filtered (->> tasks
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

          ;; Resolve dependencies: include all tasks that the initially filtered tasks depend on
          selected-names (set (map :name initially-filtered))
          all-required-names (resolve-dependencies tasks selected-names)

          ;; Filter the original task list to include only required tasks
          final-tasks-list (filter (fn [task]
                                     (contains? all-required-names (:name task)))
                                   tasks)

          longest-name-char-count (when (seq final-tasks-list)
                                    (apply max (map #(count (:name %)) final-tasks-list)))

          ;; Build all task components
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

          ;; Add watchdog that depends on all tasks
          task-sys (assoc task-sys :watchdog (component/using
                                              (watchdog/create {:exit-mode exit-mode})
                                              (mapv keyword (keys task-sys))))]
      (component/map->SystemMap task-sys))))
