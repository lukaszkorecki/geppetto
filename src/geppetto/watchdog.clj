(ns geppetto.watchdog
  (:require
   [com.stuartsierra.component :as component]
   [geppetto.log :as log]))

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
                                                   unclean-exit-count (->> state
                                                                           vals
                                                                           (filter #(and (not (:alive? %))
                                                                                         (not (:clean-exit? %))))
                                                                           count)]
                                               (when (zero? running-tasks-count)

                                                 (log/emit {:marker "EXITING"
                                                            :line "All tasks have exited. Exit stage left."})

                                                 (stop-fn {:exit 0}))

                                               (when (and (= exit-mode :fail-fast) ;; TODO: better name?
                                                          (pos? unclean-exit-count))
                                                 (log/emit {:marker "FAILURE"
                                                            :line "One or more tasks exited with an error"})

                                                 (stop-fn {:exit 11})))))
        (assoc this :store store))))

  (stop [this]
    (if (:store this)
      (do
        (remove-watch store ::process-watcher)
        (assoc this :store nil))
      this))

  IWatch
  (mark-started [this {:keys [name]}]
    (swap! store assoc name {:alive? true}))

  (mark-exited [this {:keys [name clean?]}]
    (swap! store assoc name {:alive? false :clean-exit? clean?})))

(defn create [a] (map->Watchdog a))
