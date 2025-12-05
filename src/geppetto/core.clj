(ns geppetto.core
  (:gen-class)
  (:require
   [com.stuartsierra.component :as component]
   [geppetto.config :as config]
   geppetto.logger
   [mokujin.log :as log]
   [geppetto.watchdog :as watchdog]
   [geppetto.task :as task]))

(set! *warn-on-reflection* true)

(def sys (atom nil))

(defn- build-system [tasks exit-mode]
  (let [task-sys (->> tasks
                      (map (fn [{:keys [name depends_on] :as task-def}]
                             (let [task (task/create task-def)
                                   dependencies (mapv keyword (seq depends_on))
                                   task (component/using
                                         task
                                         (vec (concat [:watchdog] dependencies)))]

                               (hash-map (keyword name) task))))
                      (into {}))

        task-sys (assoc task-sys :watchdog (watchdog/create {:exit-mode exit-mode
                                                             :expected-count (count tasks)
                                                             :stop-fn (fn [{:keys [exit]}]
                                                                        (shutdown-agents)
                                                                        (System/exit exit))}))]

    (component/map->SystemMap task-sys)))

(defn -main [& args]
  (let [conf-path (str (first args))
        {:keys [tasks _settings] :as _conf} (config/load! conf-path)
        ;; TODO: Parse from CLI flags once implemented
        ;; Options: :keep-going, :fail-fast, :exit-on-any-completion, :exit-on-all-completion
        exit-mode :fail-fast
        sys-map (build-system tasks exit-mode)]

    (log/with-context {:event "START"}
      (log/infof "Starting geppetto with config %s - %s tasks\n" conf-path (dec (count sys-map))))
    (reset! sys (component/start-system sys-map))

    (while true
      (Thread/sleep 1000))))
