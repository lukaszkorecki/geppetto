(ns geppetto.watchdog
  (:require
   [geppetto.task :as task]
   [com.stuartsierra.component :as component]
   [mokujin.log :as log]))

(defn- should-exit?
  "Determines if geppetto should exit based on exit-mode and current task states.
  Returns {:exit exit-code :reason reason-str} if should exit, nil otherwise."
  [exit-mode tasks]

  (let [running (filter task/alive? (vals tasks))
        exited (filter (comp not task/alive?) (vals tasks))
        failed (filter (fn [t] (and (not (task/alive? t))
                                    (not (zero? (task/exit-code t)))))
                       exited)]


    (log/debugf ">>>  %s" (update-vals tasks task/alive?))

    (case exit-mode
      :keep-going nil
      :fail-fast (when (seq failed)
                   {:exit 1
                    :reason (str "Tasks failed: " (->> failed (map :name) sort vec))})
      :exit-on-any-completion (when (seq exited)
                                {:exit (if (seq failed) 1 0)
                                 :reason (str "Task completed: " (->> exited first :name))})
      :exit-on-all-completion (when (empty? running)
                                {:exit (if (seq failed) 1 0)
                                 :reason "All tasks completed"})
      nil)))

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
                            (log/with-context (assoc exit-info :event "WATCHDOG_EXIT")
                              (log/warn "Watchdog triggering exit"))
                            (reset! running? false)
                            (stop-fn exit-info))
                          (recur))))]

        (log/info "watchdog started" {:event "START" :task-count task-count :exit-mode exit-mode})
        (assoc this :store store :watcher-thread watcher :running? running?))))

  (stop [this]
    (when (:running? this)
      (reset! running? false)
      (when-let [w (:watcher-thread this)]
        (future-cancel w))
      (log/info "watchdog stopped" {:event "STOP"}))
    (assoc this :store nil :watcher-thread nil :running? nil)))

(defn create [a] (map->Watchdog a))
