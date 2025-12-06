(ns geppetto.watchdog
  (:require
   [geppetto.task :as task]
   [com.stuartsierra.component :as component]
   [mokujin.log :as log]))

(def valid-modes
  #{::fail-fast
    ::exit-on-any-completion ;; are this and fail-fast the same?
    ::keep-going ;; rename to something short but clear
    })

(defn- should-exit?
  "Determines if geppetto should exit based on exit-mode and current task states.
  Returns {:exit exit-code :reason reason-str} if should exit, nil otherwise."
  [exit-mode tasks]
  (log/with-context {:task "watchdog"}
    (let [running (filter task/alive? (vals tasks))
          exited (filter (comp not task/alive?) (vals tasks))
          failed (filter (fn [t] (and (not (task/alive? t))
                                      (not (zero? (task/exit-code t)))))
                         exited)]

      (log/debugf "Watchdog checking exit conditions: running=%d exited=%d failed=%d"
                  (count running) (count exited) (count failed))

      (->> (seq exited)
           (mapv (fn [exited-task]
                   (log/warnf "Task '%s' has exited with code %s"
                              (:name exited-task)
                              (task/exit-code exited-task)))))

      (cond
       (and (= exit-mode ::fail-fast) (seq failed))
       (do
         (log/debug "Fail-fast triggered by failed tasks" {:event "EXITING"})
         {:exit 1
          :reason (str "Tasks failed: " (->> failed (map :name) sort vec))})

       (and (= exit-mode ::exit-on-any-completion) (seq exited))
       (do
         (log/debug "Exit-on-any-completion triggered by exited tasks" {:event "EXITING"})
         {:exit (if (seq failed) 1 0)
          :reason (str "Task completed: " (->> exited first :name))})

       (empty? running)
       (do
         (log/debug "Exit-on-all-completion triggered - all tasks completed"
                    {:event "EXITING"})
         {:exit (if (seq failed) 1 0)
          :reason "All tasks completed"})

       :else
       nil))))

(defrecord Watchdog [;; inputs
                     exit-mode
                     stop-fn
                     ;; internal state
                     store
                     watcher-thread
                     running?]
  component/Lifecycle
  (start [this]
    (if (:store this)
      this
      (let [store (atom {})
            running? (atom true)
            tasks (dissoc this :exit-mode :stop-fn :store :watcher-thread :running?)
            task-count (count tasks)
            watcher (future
                      (loop []
                        (Thread/sleep 500)
                        (when @running?
                          (when-let [exit-info (should-exit? exit-mode tasks)]
                            (log/with-context {:task "watchdog"}
                              (log/warn "Exiting")
                              (log/warnf "Reason: %s code=%s" (:reason exit-info) (:exit exit-info)))
                            (reset! running? false)
                            (stop-fn exit-info))
                          (recur))))]

        #_{:clj-kondo/ignore [:mokujin.log/log-message-not-string]}
        (log/info (format "started %s tasks exit-mode=%s" task-count (name exit-mode))
                  {:task "watchdog" :event "START"})
        (assoc this :store store :watcher-thread watcher :running? running?))))

  (stop [this]
    (when (:running? this)
      (reset! running? false)
      (when-let [w (:watcher-thread this)]
        (future-cancel w))
      (log/info "stopped" {:task "watchdog" :event "STOP"}))
    (assoc this :store nil :watcher-thread nil :running? nil)))

(defn create [a] (map->Watchdog a))
