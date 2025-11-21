(ns geppetto.core
  (:gen-class)
  (:require
   [com.stuartsierra.component :as component]
   [geppetto.config :as config]
   [geppetto.log :as log]
   [geppetto.task :as task]))

(set! *warn-on-reflection* true)

;; Process management

(defn- build-system [tasks]
  (let [task-sys (->> tasks
                      (map (fn [{:keys [name depends_on] :as task-def}]
                             (let [task (task/create task-def)
                                   task (if-let [dependencies (not-empty (mapv keyword (seq depends_on)))]
                                          (component/using task dependencies)
                                          task)]
                               (hash-map (keyword name) task))))
                      (into {}))]
    (component/map->SystemMap task-sys)))

;; TODO: add a bit more machinery to make this whole thing more manageable
;; - scheduled thread pool executor to poll all processes for status to log when they exit
;; - a thread pool to manage all of these threads

;; TODO use c.t.clii

(def sys (atom nil))

(defn -main [& args]
  (Runtime/.addShutdownHook (Runtime/getRuntime)
                            (Thread. ^Runnable (fn []
                                                 (shutdown-agents)
                                                 (log/emit {:marker "EXIT"
                                                            :line "Shutting down geppetto..."})
                                                 (swap! sys #(when %
                                                               (component/stop %))))))
  (let [conf-path (str (first args))
        {:keys [tasks _settings] :as _conf} (config/load! conf-path)

        sys-map (build-system tasks)]

    (log/emit {:marker "START"
               :line (format "Starting geppetto with config %s - %s tasks\n" conf-path (count sys-map))})
    (reset! sys (component/start-system sys-map))

    (while true
      (Thread/sleep 100))))
