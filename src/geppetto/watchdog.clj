(ns geppetto.watchdog
  (:require
   [geppetto.service :as service]
   [com.stuartsierra.component :as component]
   [mokujin.log :as log]))

(def valid-modes
  {::on-failure "exit immediately when ANY service fails (non-zero exit)"
   ::any "exit when ANY service completes (successful or failed)"
   ::all "wait for ALL services to complete"})

(def recently-exited (atom []))

(defprotocol IWatchdog
  (wait-for-exit [this] "Blocks until watchdog signals exit. Returns exit info map with :exit and :reason."))

(defn- should-exit?
  "Determines if geppetto should exit based on exit-mode and current service states.
  Returns {:exit exit-code :reason reason-str} if should exit, nil otherwise."
  [exit-mode services]
  (log/with-context {:service "watchdog"}
    (let [running (filter service/alive? (vals services))
          exited (filter (comp not service/alive?) (vals services))
          failed (filter (fn [t] (and (not (service/alive? t))
                                      (not (zero? (service/exit-code t)))))
                         exited)]

      (log/debugf "Watchdog checking exit conditions: running=%d exited=%d failed=%d"
                  (count running) (count exited) (count failed))

      ;; print out newly exited services, but only once
      (->> (seq exited)
           (remove (fn [exited-svc]
                     (some #(= (:name exited-svc) (:name %)) @recently-exited)))
           (mapv (fn [exited-svc]
                   (let [exit-code (service/exit-code exited-svc)]
                     (log/with-context {:service (:name exited-svc)}
                       (log/warnf "Service has exited with code %s" exit-code))
                     (swap! recently-exited conj {:name (:name exited-svc)
                                                  :exit-code exit-code})))))

      (cond
        (and (= exit-mode ::on-failure) (seq failed))
        (do
          (log/debug "on-failure mode: exiting due to failed services" {:event "EXITING"})
          {:exit 1
           :reason (str "Services failed: " (->> failed (map :name) sort vec))})

        (and (= exit-mode ::any) (seq exited))
        (do
          (log/debug "any mode: exiting due to service completion" {:event "EXITING"})
          {:exit (if (seq failed) 1 0)
           :reason (str "Service completed: " (->> exited first :name))})

        (empty? running)
        (do
          (log/debug "all mode: all services completed" {:event "EXITING"})
          {:exit (if (seq failed) 1 0)
           :reason "All services completed"})

        :else
        nil))))

(defrecord Watchdog [;; inputs
                     exit-mode
                     ;; internal state
                     store
                     watcher-thread
                     running?
                     shutdown-promise]
  component/Lifecycle
  (start [this]
    (if (:store this)
      this
      (let [store (atom {})
            running? (atom true)
            shutdown-promise (promise)
            services (dissoc this :exit-mode :store :watcher-thread :running? :shutdown-promise)
            service-count (count services)
            watcher (future
                      (loop []
                        (Thread/sleep 500)
                        (when @running?
                          (when-let [exit-info (should-exit? exit-mode services)]
                            (log/with-context {:service "watchdog"}
                              (log/warn "Exiting")
                              (log/warnf "Reason: %s code=%s" (:reason exit-info) (:exit exit-info)))
                            (deliver shutdown-promise exit-info))
                          (recur))))]

        #_{:clj-kondo/ignore [:mokujin.log/log-message-not-string]}
        (log/info (format "started %s services exit-mode=%s" service-count (name exit-mode))
                  {:service "watchdog" :event "START"})
        (assoc this :store store :watcher-thread watcher :running? running? :shutdown-promise shutdown-promise))))

  (stop [this]
    (when (:running? this)
      (reset! running? false)
      (when-let [w (:watcher-thread this)]
        (future-cancel w))
      (log/info "stopped" {:service "watchdog" :event "STOP"}))
    (assoc this :store nil :watcher-thread nil :running? nil))

  IWatchdog
  (wait-for-exit [this]
    @(:shutdown-promise this)))

(defn create [a] (map->Watchdog a))
