(ns geppetto.errors
  "Convinence around building and raising typed errors."
  (:require
   [mokujin.log :as log]))

(def registry
  {::unknown {:message "An unknown error occurred."}
   ;; fatal errors
   ::config-not-found {:message "Configuration file not found." :exit-code 1}
   ::invalid-config {:message "Configuration file is invalid." :exit-code 2}
   ::task-dir-doesnt-exist {:message "Task working directory does not exist." :exit-code 3}
   ::task-env-file-doesnt-exist {:message "Task environment file does not exist." :exit-code 3}
   ::invalid-task-dependency {:message "Task has an invalid dependency." :exit-code 4}})

(def ^{:dynamic true
       :doc "Set this to false to prevent errors from exiting the JVM. Only used in tests."}
  *really-exit?* true)

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
    (if [exit-code (and (number? exit-code) (pos? exit-code))]
      ;; fatal error - exit the JVM, unless suppressed
      (if *really-exit?*
        (do
          (flush)
          (binding [*out* *err*]
            (flush))
          (log/with-context {:level "FATAL"}
            (if-let [err-type (-> exc ex-data ::type)]
              (log/errorf "[%s] %s\n" err-type (ex-message exc))
              (log/errorf "%s\n" (ex-message exc))))
          (System/exit exit-code))
        ;; suppressed fatal error exit - used in tests
        (throw (ex-info "suppressed fatal error" (ex-data exc) exc)))
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
