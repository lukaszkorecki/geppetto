(ns geppetto.task
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [com.stuartsierra.component :as component]
   [geppetto.watchdog :as dog]
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

(defrecord ATask [command
                  name
                  tags
                  env
                  dir
                  ;; TODO
                  env_command
                  env_file

                  ;; internal state:
                  ;; the process handle thing
                  process
                  ;; threads streaming stdout and stderr (not futures - no threadpool limits)
                  out-thread
                  err-thread
                  ;; boolean-atom
                  running?]
  component/Lifecycle
  (start [this]
    (if (and (:running? this) @(:running? this))
      this
      (let [env (merge env {"GP_ID" name})
            running? (atom true)
            {:keys [out err] :as process} (proc/process command {:extra-env env
                                                                 :dir dir
                                                                 :shutdown (fn [proc]
                                                                             (reset! running? false)
                                                                             (dog/mark-exited (:watchdog this)
                                                                                              {:name name
                                                                                               :clean? (zero? (:exit @proc))})
                                                                             (proc/destroy-tree proc))})
            _ (wait-for-process process)

            stdout-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader out)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   (log/emit {:marker name :line line :dev :out})
                                   (recur))))))

            stderr-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader err)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   (log/emit {:marker name :line line :dev :err})
                                   (recur))))))]
        (.start stdout-thread)
        (.start stderr-thread)

        (dog/mark-started (:watchdog this) {:name name})
        (assoc this
               :running? running?
               :process process
               :out-thread stdout-thread
               :err-thread stderr-thread))))

  (stop [this]
    (if @(:running? this)
      (do
        (when-let [process (:process this)]
          (proc/destroy-tree process))

        (reset! running? false)
        (dog/mark-exited (:watchdog this) {:name name :clean? true})

        (when-let [t (:out-thread this)] (Thread/.interrupt t))

        (when-let [t (:err-thread this)] (Thread/.interrupt t))

        (assoc this
               :running? (atom false)
               :process nil
               :out-thread nil
               :err-thread nil))
      this)))

(defn create [task-def]
  (map->ATask task-def))
