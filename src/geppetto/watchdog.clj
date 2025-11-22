(ns geppetto.watchdog
  (:require
   [com.stuartsierra.component :as component]
   [mokujin.log :as log]))

(defprotocol IWatch ; lol
  (mark-started [this {:keys [name]}])
  (mark-exited [this {:keys [name clean?]}]))

(defrecord Watchdog [;; inputs
                     expected-count
                     exit-mode
                     stop-fn
                     ;; internal state
                     store
                     watcher]
  component/Lifecycle
  (start [this]
    (if (:store this)
      this
      (let [store (atom {})]
        (add-watch store ::process-watcher (fn [_k _ref _os state]
                                             (let [running-tasks-count (count (filter :alive? (vals state)))
                                                   unclean-exits (into {} (filter (fn [[_ {:keys [alive?]}]] alive?) x))]
                                               (when (zero? running-tasks-count)
                                                 (log/warn "Exiting. All tasks have exited. Exit stage left."
                                                           {:level "WARN"})
                                                 (remove-watch _ref ::process-watcher)
                                                 (stop-fn {:exit 0}))

                                               (when (and (= exit-mode :fail-fast) ;; TODO: better name?
                                                          (seq unclean-exits))
                                                 (remove-watch _ref ::process-watcher)
                                                 (log/with-context {:level "ERROR"}
                                                   (log/infof "One or more tasks exited with an error %s"
                                                              (->> unclean-exits keys sort vec)))

                                                 (stop-fn {:exit 11})))))

        (log/info "watchdog started" {:event "START" :task-count expected-count})
        (assoc this :store store))))

  (stop [this]
    (if (:store this)
      (do
        (remove-watch store ::process-watcher)
        (log/info "bye" {:event "STOP"})

        (assoc this :store nil))
      this))

  IWatch
  (mark-started [_this {:keys [name]}]
    (swap! store assoc name {:alive? true}))

  (mark-exited [_this {:keys [name clean?]}]
    (swap! store assoc name {:alive? false :clean-exit? clean?})))

(defn create [a] (map->Watchdog a))
