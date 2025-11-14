(ns geppetto.core
  (:gen-class)
  (:require
   [babashka.fs :as fs]
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [com.stuartsierra.component :as component]
   flatland.ordered.map
   [geppetto.config :as config]
   [geppetto.log :as log])
  (:import
   [java.io BufferedReader]))

(set! *warn-on-reflection* true)

;; Process management

(defn wait-for-process
  "Wait for process to finish and return exit code"
  [process]
  (Thread/sleep 100)
  (if (proc/alive? process)
    true
    (let [exit-code (:exit @process)]
      (when-not (zero? exit-code)
        (printf "ERROR: process exited with code %s\n" exit-code)
        (System/exit exit-code)))))

(defrecord ATask [command name tags env
                  ;; internal state:
                  ;; the process handle thing
                  process
                  ;; threads streaming stdout and stderr (not futures - no threadpool limits)
                  out-thread
                  err-thread]
  component/Lifecycle
  (start [this]
    (if (:running? this)
      this
      (let [env (merge env {"GP_ID" name})
            {:keys [out err] :as process} (proc/process command {:extra-env env
                                                                 :shutdown proc/destroy-tree})
            _ (wait-for-process process)
            pid (.pid ^java.lang.Process (:proc process))
            stdout-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader out)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   (log/emit {:marker name :pid pid :line line :dev :out})
                                   (recur))))))

            stderr-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader err)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   (log/emit {:marker name :pid pid :line line :dev :err})
                                   (recur))))))]
        (.start stdout-thread)
        (.start stderr-thread)
        (assoc this
               :running? true
               :process process
               :out-thread stdout-thread
               :err-thread stderr-thread))))

  (stop [this]
    (if (:running? this)
      (do
        (when-let [process (:process this)]
          (proc/destroy-tree process))

        (when-let [t (:out-thread this)]
          (Thread/.interrupt t))

        (when-let [t (:err-thread this)]
          (Thread/.interrupt t))

        (assoc this
               :running? false
               :process nil
               :out-thread nil
               :err-thread nil))
      this)))

(defn- build-system [tasks]
  (let [task-sys (->> tasks
                      (map (fn [{:keys [name deps] :as task-def}]
                             (let [task (map->ATask task-def)
                                   task (if (seq deps)
                                          (component/using task (mapv keyword deps))
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
                                                 (printf "Shutting down geppetto...\n")
                                                 (swap! sys #(when %
                                                               (component/stop %)))
                                                 (printf "Shutdown complete.\n"))))
  (let [conf-path (str (first args))
        {:keys [tasks _settings] :as _conf} (config/load! conf-path)

        sys-map (build-system tasks)]

    (printf "Starting geppetto with config %s - %s tasks\n" conf-path (count sys-map))
    (reset! sys (component/start-system sys-map))

    (while true
      (Thread/sleep 100))))
