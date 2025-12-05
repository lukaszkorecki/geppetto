(ns geppetto.task
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [com.stuartsierra.component :as component]
   [geppetto.watchdog :as dog]
   [geppetto.logger :as logger]
   [mokujin.log :as log])
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
                  process
                  out-thread
                  err-thread
                  monitor-thread]
  component/Lifecycle
  (start [this]
    (if (:process this)
      this
      (let [env (merge env {"GP_ID" name})
            {:keys [out err] :as process} (proc/process command {:extra-env env :dir dir})

            stdout-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader out)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   #_{:clj-kondo/ignore [:mokujin.log/log-message-not-string]}
                                   (log/info line {:task (logger/colorize name)})
                                   (recur))))))

            stderr-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader err)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   #_{:clj-kondo/ignore [:mokujin.log/error-log-map-args]}
                                   (log/error line {:task (logger/colorize name) :dev "stderr"})
                                   (recur))))))

            monitor-thread (Thread.
                            (fn []
                              (try
                                (let [exit-code (:exit @process)]
                                  (dog/mark-exited (:watchdog this) name (zero? exit-code)))
                                (catch Exception e
                                  (log/error "Error monitoring process" {:task name :error (ex-message e)})))))]

        (.start stdout-thread)
        (.start stderr-thread)
        (.start monitor-thread)

        (dog/mark-started (:watchdog this) name)
        (assoc this
               :process process
               :out-thread stdout-thread
               :err-thread stderr-thread
               :monitor-thread monitor-thread))))

  (stop [this]
    (when-let [process (:process this)]
      (when (proc/alive? process)
        (proc/destroy-tree process))

      (when-let [t (:out-thread this)] (.interrupt t))
      (when-let [t (:err-thread this)] (.interrupt t))
      (when-let [t (:monitor-thread this)] (.interrupt t))

      (assoc this
             :process nil
             :out-thread nil
             :err-thread nil
             :monitor-thread nil))))

(defn create [task-def]
  (map->ATask task-def))
