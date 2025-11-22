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

(defn- build-system [tasks]
  (let [task-sys (->> tasks
                      (map (fn [{:keys [name depends_on] :as task-def}]
                             (let [task (task/create task-def)
                                   dependencies (mapv keyword (seq depends_on))
                                   task (component/using
                                         task
                                         (vec (concat [:watchdog] dependencies)))]

                               (hash-map (keyword name) task))))
                      (into {}))

        task-sys (assoc task-sys :watchdog (watchdog/create {:exit-mode #_:keep-going :fail-fast
                                                             :expected-count (count tasks)
                                                             :stop-fn (fn [{:keys [exit]}]
                                                                        (when-let [sys' @sys]
                                                                          (component/stop sys'))
                                                                        (shutdown-agents)
                                                                        (System/exit exit))}))]

    (component/map->SystemMap task-sys)))

;; TODO: add a bit more machinery to make this whole thing more manageable
;; - scheduled thread pool executor to poll all processes for status to log when they exit
;; - a thread pool to manage all of these threads

;; TODO use c.t.clii

(defn -main [& args]
  (Runtime/.addShutdownHook (Runtime/getRuntime)
                            (Thread. ^Runnable (fn []
                                                 (shutdown-agents)
                                                 (when-let [sys' @sys]
                                                   (component/stop sys')))))
  (let [conf-path (str (first args))
        {:keys [tasks _settings] :as _conf} (config/load! conf-path)

        sys-map (build-system tasks)]

    (log/with-context {:event "START"}
      (log/infof "Starting geppetto with config %s - %s tasks\n" conf-path (dec (count sys-map))))
    (reset! sys (component/start-system sys-map))

    (while true
      (Thread/sleep 100))))
