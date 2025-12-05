(ns geppetto.watchdog
  (:require
   [com.stuartsierra.component :as component]
   [mokujin.log :as log]))

(defprotocol IWatch
  (mark-started [this task-name])
  (mark-exited [this task-name clean?]))

(defn- should-exit?
  "Determines if geppetto should exit based on exit-mode and current task states.
  Returns {:exit exit-code :reason reason-str} if should exit, nil otherwise."
  [exit-mode tasks-state]
  (let [running (filter :alive? (vals tasks-state))
        exited (filter (comp not :alive?) (vals tasks-state))
        failed (filter (fn [t] (and (not (:alive? t))
                                    (not (:clean-exit? t))))
                       exited)]
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
                     expected-count
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
            watcher (future
                      (loop []
                        (Thread/sleep 500)
                        (when @running?
                          (when-let [exit-info (should-exit? exit-mode @store)]
                            (log/warn "Watchdog triggering exit" (assoc exit-info :event "WATCHDOG_EXIT"))
                            (reset! running? false)
                            (stop-fn exit-info))
                          (recur))))]

        (log/info "watchdog started" {:event "START" :task-count expected-count :exit-mode exit-mode})
        (assoc this :store store :watcher-thread watcher :running? running?))))

  (stop [this]
    (when (:running? this)
      (reset! running? false)
      (when-let [w (:watcher-thread this)]
        (future-cancel w))
      (log/info "watchdog stopped" {:event "STOP"}))
    (assoc this :store nil :watcher-thread nil :running? nil))

  IWatch
  (mark-started [_this task-name]
    (swap! store assoc task-name {:name task-name :alive? true}))

  (mark-exited [_this task-name clean?]
    (swap! store update task-name assoc :alive? false :clean-exit? clean?)))

(defn create [a] (map->Watchdog a))
