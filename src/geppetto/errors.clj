(ns geppetto.errors
  "Convinence around building and raising typed errors."
  (:require
   [mokujin.log :as log]
   [geppetto.exit :as exit]))

(def registry
  {::unknown {:message "An unknown error occurred."}
   ;; fatal errors
   ::config-not-found {:message "Configuration file not found." :exit-code 1}
   ::invalid-config {:message "Configuration file is invalid." :exit-code 2}
   ::service-dir-doesnt-exist {:message "Service working directory does not exist." :exit-code 3}
   ::service-env-file-doesnt-exist {:message "Service environment file does not exist." :exit-code 3}
   ::invalid-service-dependency {:message "Service has an invalid dependency." :exit-code 4}
   ::no-matching-services {:message "No matching services found to launch." :exit-code 5}
   ::no-services-in-config {:message "No services defined in configuration." :exit-code 6}})

(defn type->exc
  ([type]
   (type->exc type nil))
  ([type pre-hook]
   (let [{:keys [message exit-code]} (get registry type (get registry ::unknown))]
     (ex-info message
              (cond-> {:type (or type ::unknown)
                       :exit-code exit-code}

                pre-hook (assoc :pre-hook pre-hook))))))

(defn throw-or-exit
  "Throws given internal exception or exits the JVM if it's a fatal error.
  Exception data must contain :exit-code key for fatal errors.
  If it contains :pre-hook  (a no-arity fn), it will be called before exiting/throwing
  "
  [exc]
  (let [{:keys [pre-hook exit-code]} (ex-data exc)]
    (when (fn? pre-hook)
      (pre-hook))
    (if (and (number? exit-code) (pos? exit-code))
      ;; fatal error - exit the JVM, unless suppressed
      (do
        (flush)
        (log/with-context {:level "FATAL" :task "geppetto"}
          (if-let [err-type (-> exc ex-data ::type)]
            (log/errorf "[%s] %s\n" err-type (ex-message exc))
            (log/errorf "%s\n" (ex-message exc))))
        (exit/exit! exit-code))
      ;; non-fatal error - just re-throw
      (throw exc))))

(defn raise!
  "Given error type, builds it and throws it, potentially exiting the JVM.
   If given an exception, raises it if it's a known error."
  ([thing]
   (raise! thing nil))
  ([thing pre-hook]
   (if (instance? Throwable thing)
     (throw-or-exit thing)
     ;; assume internal type
     (-> thing (type->exc pre-hook) throw-or-exit))))
