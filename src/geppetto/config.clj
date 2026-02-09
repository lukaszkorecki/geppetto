(ns geppetto.config
  (:refer-clojure :exclude [resolve])
  (:require
   [mokujin.log :as log]
   [geppetto.errors :as errors]
   [geppetto.watchdog :as watchdog]
   [babashka.fs :as fs]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [clojure.walk :as walk]
   [malli.core :as m]
   [malli.error :as me]))

;; Schema & config processing
(def Service
  [:map
   [:name
    {:description "The unique name of the service"
     :min 1}
    :string]

   [:command
    {:description "The command to run"
     :min 1}
    :string]

   [:tags
    {:description "A list of tags to categorize or group the service"
     :optional true}
    [:every [:string {:min 1 :max 32}]]]

   [:dir
    {:description "Working directory for the process"
     :optional true}
    [:string {:min 1}]]

   [:depends_on
    {:description "Other service names that this service depends on; must exist in config"
     :optional true}
    [:every :string]]

   [:env
    {:description "Environment variables for the service (values are stringified)"
     :optional true}
    [:map-of :keyword [:or :string :int :boolean]]]

   [:env_command
    {:description "Shell command to run that provides env vars for the service - usually this would be your secret manager of choice"
     :optional true}
    :string]

   [:parse_json_logs
    {:description "Parse JSON log lines and output as readable YAML"
     :optional true}
    :boolean]])

(def ServiceConfig
  [:map
   ;; TODO - configure:
   ;; - output format (text or json)
   ;; - default workdir, to override resolving relative paths based on config file location
   [:settings
    {:optional true :description "Global settings for the service runner"}
    [:map
     [:root_dir
      {:description "Default working directory for services with relative paths. When not specified, the config file location is used"
       :optional true
       :min 1}
      :string]
     [:exit_mode
      {:description "Exit behavior: all (wait for all), any (exit when any completes), on-failure (exit on first failure)"
       :optional true}
      (into [:enum] (map name (keys watchdog/valid-modes)))]]]

   [:services
    [:every #'Service]]])

(defn verify! [conf]
  (when-not (m/validate ServiceConfig conf)
    (errors/raise! ::errors/invalid-config (fn []
                                             (log/with-context {:service "config-parser"}
                                               (log/error "invalid config!"))
                                             (-> (m/explain ServiceConfig conf)
                                                 me/with-spell-checking
                                                 me/humanize
                                                 (yaml/generate-string)
                                                 println))))
  conf)

(defn- resolve-service-dir [{:keys [dir] :as svc}
                            {:keys [root-dir]}]
  (log/with-context {:service (:name svc)}
    (cond
      ;; bail out - nothing to do
      (not dir)
      svc

      ;; bail out - nothing to do
      (and (not-empty dir) (fs/absolute? dir) (fs/exists? dir))
      (do
        (log/debugf "Service '%s' has an absolute working directory that exists: %s"
                    (:name svc) dir)
        svc)

      ;; we have a dir, it's absolute, but it doesn't exist
      (and (not-empty dir) (fs/absolute? dir) (not (fs/exists? dir)))
      (errors/raise! ::errors/service-dir-doesnt-exist
                     #(log/errorf "FATAL: service specifies a working directory that doesn't exist: %s" dir))

      ;; we have a dir, it's relative - resolve it against root-dir
      (and (not-empty dir) (not (fs/absolute? dir)))
      (let [final-path (-> (fs/path root-dir dir)
                           fs/absolutize
                           fs/normalize
                           str)]
        (log/debugf "Service has a relative working directory; resolved to: %s" final-path)
        (if (fs/exists? final-path)
          (assoc svc :dir final-path)
          (errors/raise! ::errors/service-dir-doesnt-exist
                         #(log/errorf "FATAL: service specifies a working directory that doesn't exist: %s" final-path)))))))

(defn resolve
  "Resolve relative paths in config based on root-dir. In the future this is where we can add env-file support"
  [conf {:keys [root-dir]}]
  (-> conf
      (update :services (fn [services]
                          (->> services
                               (mapv (fn [svc]
                                       (-> svc
                                           (resolve-service-dir {:root-dir root-dir})
                                           (update :tags set)))))))))

;; FIXME: why do we need to do this?
(def ordered-map-class
  (class (yaml/parse-string "foo: bar")))

;; Environment variable expansion
(def ^:private env-var-pattern
  "Matches ${VAR} or ${VAR:-default}"
  #"\$\{([^}:]+)(?::-([^}]*))?\}")

(defn- expand-env-var
  "Expand a single env var match. Returns replacement string."
  [[_ var-name default-value]]
  (if-let [value (System/getenv var-name)]
    value
    (do
      (when-not default-value
        (log/warnf "Environment variable '%s' is not set" var-name))
      (or default-value ""))))

(defn- expand-env-vars
  "Expand all ${VAR} and ${VAR:-default} patterns in a string."
  [s]
  (str/replace s env-var-pattern expand-env-var))

(defn- expand-env-in-config
  "Walk config and expand environment variables in all string values."
  [conf]
  (walk/postwalk
   (fn [x]
     (if (string? x)
       (expand-env-vars x)
       x))
   conf))

(defn- normalize-path
  "Expand home dir and normalize path to absolute string."
  [path]
  (some-> path
          fs/expand-home
          fs/absolutize
          fs/normalize
          str))

(defn load!
  "Load and validate config from path.
   Options:
     :root-dir - override root directory for resolving relative paths

   Priority for root directory:
     1. :root-dir option (CLI --root flag)
     2. settings.root_dir from config file
     3. config file's parent directory (default)"
  ([conf-path] (load! conf-path {}))
  ([conf-path {:keys [root-dir]}]
   (when (str/blank? conf-path)
     (errors/raise! ::errors/blank-config-path))
   (let [conf-path (str (fs/expand-home conf-path))
         _ (when-not (fs/exists? conf-path)
             (errors/raise! ::errors/config-not-found))
         config-file-dir (normalize-path (fs/parent conf-path))
         conf-data (->> (yaml/parse-string (slurp conf-path))
                        ;; convert ordered-map to regular maps, they're easier to work with
                        (walk/postwalk (fn [thing]
                                         (if (instance? ordered-map-class thing)
                                           (into {} thing)
                                           thing)))
                        ;; expand ${VAR} and ${VAR:-default} patterns
                        expand-env-in-config)
         ;; Priority: CLI --root > settings.root_dir > config-file-dir
         effective-root (or (normalize-path root-dir)
                            (normalize-path (get-in conf-data [:settings :root_dir]))
                            config-file-dir)]
     (-> conf-data
         verify!
         (resolve {:root-dir effective-root})))))
