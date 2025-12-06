(ns geppetto.task
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [com.stuartsierra.component :as component]
   [mokujin.log :as log])
  (:import
   [java.io BufferedReader]))

(set! *warn-on-reflection* true)

(defprotocol ITask
  (status [this] "Get status of a task")
  (alive? [this] "Return true if task process is still running")
  (exit-code [this] "Return exit code of finished process, or nil if still running"))

(defrecord ATask [command
                  name
                  tags
                  env
                  dir
                  ;; TODO
                  env_command
                  env_file

                  ;; internal state:
                  process
                  out-thread
                  err-thread
                  monitor-thread]

  ITask
  (status [this]
    (if-let [process (:process this)]
      (if (proc/alive? process)
        {:status ::alive}
        (let [exit-code (:exit @process)]
          (if (zero? exit-code)
            {:status ::clean-exit :exit-code 0}
            {:status ::error-exit :exit-code exit-code})))
      {:status ::gone :exit-code -1}))

  (alive? [this]
    (= ::alive (:status (status this))))

  (exit-code [this]
    (when-let [process (:process this)]
      (:exit @process)))

  component/Lifecycle
  (start [this]
    (if (:process this)
      this
      (let [env (merge env {"geppetto.task-name" name})
            {:keys [out err] :as process} (proc/process command {:extra-env env
                                                                 :dir dir})

            stdout-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader out)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   #_{:clj-kondo/ignore [:mokujin.log/log-message-not-string]}
                                   (log/info line {:task name :dev "stdout"})
                                   (recur))))))

            stderr-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader err)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   #_{:clj-kondo/ignore [:mokujin.log/error-log-map-args]}
                                   (log/error line {:task name :dev "stderr"})
                                   (recur))))))]

        (.start stdout-thread)
        (.start stderr-thread)

        (assoc this
               :process process
               :out-thread stdout-thread
               :err-thread stderr-thread))))

  (stop [this]
    (log/warn "Stopping" {:task name})
    (when-let [process (:process this)]
      (when (proc/alive? process)
        (proc/destroy-tree process))

      (proc/check process)

      (when-let [t (:out-thread this)] (Thread/.interrupt t))
      (when-let [t (:err-thread this)] (Thread/.interrupt t))

      (assoc this
             :process nil
             :out-thread nil
             :err-thread nil))))

(defn create [task-def]
  (map->ATask task-def))
