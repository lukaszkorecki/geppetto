(ns geppetto.core
  (:gen-class)
  (:require [babashka.process :as proc]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]
            [com.stuartsierra.component :as component]
            [clj-yaml.core :as yaml]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.walk :as walk]
            flatland.ordered.map))

;; Schema & config processing
(def Task
  [:map
   [:command {:min 1} :string]
   [:name {:min 1} :string]
   [:tags {:optional true} [:every [:string {:min 1 :max 32}]]]
   [:deps {:optional true} [:every :string]]
   [:env
    ;; FIXME: we need to also account for numbers
    [:map-of :keyword :string]]

   ;; TODO:
   ;; {:env-from {:command "...." }}
   ])

(def TaskConfig
  [:map
   [:settings {:optional true}
  ;; TODO
    [:map-of :string :string]]

   [:tasks
    [:every #'Task]]])

(defn verify-config [conf]
  (when-not (m/validate TaskConfig conf)
    (println "ERROR: invalid config:")
    (-> (m/explain TaskConfig conf)
        me/humanize
        (yaml/generate-string)
        println)
    (System/exit 13)))

(def cl
  (class (yaml/parse-string "foo: bar")))

(defn load-config [conf-path]
  (let [content (slurp conf-path)
        conf (->> (yaml/parse-string content)
                  (walk/postwalk (fn [thing]
                                   (if (instance? cl thing)
                                     (into {} thing)
                                     thing))))]
    (verify-config conf)
    conf))

;; Task output logging
(def color-codes
  {0 31 ; red
   1 32 ; green
   2 33 ; yellow
   3 34 ; blue
   4 35 ; magenta
   5 36 ; cyan
   })

(defn task-name->color-str
  "Calculate hash and return ANSI color code string for task name"
  [name]
  (let [hash-code (mod (reduce + (map int name)) 6)
        color-code (get color-codes hash-code 37)]
    (str "\u001b[" color-code "m" name "\u001b[0m")))

(defn log
  "Like print, but threadsafe"
  [s]
  (locking *out*
    (println s)
    (flush)))

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
                                 (when-let [line (.readLine rdr)]
                                   (log (format "%s [%s out] %s" (task-name->color-str name) pid line))
                                   (flush)
                                   (recur))))))

            stderr-thread (Thread.
                           (fn []
                             (with-open [rdr (io/reader err)]
                               (loop []
                                 (when-let [line (.readLine rdr)]
                                   (log (format "%s [%s err] %s" (task-name->color-str name) pid line))
                                   (flush)
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
          (.interrupt t))
        (when-let [t (:err-thread this)]
          (.interrupt t))

        (assoc this
               :running? false
               :process nil
               :out-thread nil
               :err-thread nil))
      this)))

;; TODO: add a bit more machinery to make this whole thing more manageable
;; - scheduled thread pool executor to poll all processes for status to log when they exit
;; - a thread pool to manage all of these threads

;; TODO use c.t.clii
(defn -main [& args]
  (let [conf-path (str (fs/expand-home (str (first args))))
        _ (when-not (fs/exists? conf-path)
            (println "ERROR: config file '%s' does not exist" conf-path)
            (System/exit 12))
        {:keys [tasks _settings] :as _conf} (load-config conf-path)

        ;; construct system dynamically

        sys-map (->> tasks
                     (map (fn [{:keys [name deps] :as task-def}]
                            (let [task (map->ATask task-def)
                                  task (if (seq deps)
                                         (component/using task (mapv keyword deps))
                                         task)]

                              (hash-map (keyword name) task))))

                     (into {}))]

    (printf "Starting geppetto with config %s - %s tasks\n" conf-path (count sys-map))
    (->> (component/map->SystemMap sys-map)
         component/start)

    (while true
      (Thread/sleep 100))))

;; entrypoint for bb
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
