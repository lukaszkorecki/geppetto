(ns geppetto.service
  (:require
   [babashka.process :as proc]
   [clojure.java.io :as io]
   [com.stuartsierra.component :as component]
   [geppetto.logger :as logger]
   [mokujin.log :as log])
  (:import
   [java.io BufferedReader]))

(set! *warn-on-reflection* true)

(defprotocol IService
  (status [this] "Get status of a service")
  (alive? [this] "Return true if service process is still running")
  (exit-code [this] "Return exit code of finished process, or nil if still running"))

(defrecord AService [command
                     name
                     loggable-name
                     tags
                     env
                     dir
                     ;; TODO
                     env_command
                     env_file
                     parse_json_logs

                     ;; internal state:
                     process
                     out-thread
                     err-thread
                     monitor-thread]

  IService
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
    (when-not (alive? this)
      (when-let [process (:process this)]
        (:exit @process))))

  component/Lifecycle
  (start [this]
    (if (:process this)
      this
      (let [env (merge env {"geppetto.service-name" name})
            _ (log/with-context {:service (or loggable-name name)}
                (if dir
                  (log/infof "starting '%s' in %s" command dir)
                  (log/infof "starting '%s'" command)))
            {:keys [out err] :as process} (proc/process command {:extra-env env
                                                                 :dir dir})

            stdout-thread (Thread/startVirtualThread
                           ^Runnable
                           (fn []
                             (with-open [^BufferedReader rdr (io/reader out)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   (let [formatted (logger/format-line line {:parse-json? parse_json_logs})]
                                     (log/with-context {:service (or loggable-name name) :dev "stdout"}
                                       #_{:clj-kondo/ignore [:mokujin.log/log-message-not-string]}
                                       (log/info formatted)))
                                   (recur))))))

            stderr-thread (Thread/startVirtualThread
                           ^Runnable
                           (fn []
                             (with-open [^BufferedReader rdr (io/reader err)]
                               (loop []
                                 (when-let [line (BufferedReader/.readLine rdr)]
                                   (let [formatted (logger/format-line line {:parse-json? parse_json_logs})]
                                     (log/with-context {:service (or loggable-name name) :dev "stderr"}
                                       (log/error formatted)))
                                   (recur))))))]

        (assoc this
               :process process
               :out-thread stdout-thread
               :err-thread stderr-thread))))

  (stop [this]
    (log/warn "Stopping" {:service loggable-name})
    (when-let [process (:process this)]
      (when (proc/alive? process)
        (proc/destroy-tree process))

      (when-let [t (:out-thread this)] (Thread/.interrupt t))
      (when-let [t (:err-thread this)] (Thread/.interrupt t))

      (assoc this
             :process nil
             :out-thread nil
             :err-thread nil))))

(defn create [service-def]
  (map->AService service-def))
