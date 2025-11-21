(ns geppetto.task
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [com.stuartsierra.component :as component]
   [geppetto.log :as log])
  (:import
   [java.io BufferedReader]))

(defn wait-for-process
  "Wait for process to finish and return exit code"
  [process]
  (if (proc/alive? process)
    {:status ::alive}
    (let [exit-code (:exit @process)]
      (if (zero? exit-code)
        {:status ::clean-exit :exit-code 0}
        {:status ::error-exit :exit-code exit-code}))))

(defrecord ATask [command name tags env dir
                  ;; TODO
                  env_command
                  env_file
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
                                                                 :dir dir
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


(defn create [task-def]
  (map->ATask task-def))
