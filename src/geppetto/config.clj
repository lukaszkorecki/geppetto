(ns geppetto.config
  (:refer-clojure :exclude [resolve])
  (:require
   [mokujin.log :as log]
   [geppetto.errors :as errors]
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
    {:description "Environment variables for the service (as key-value string map)"
     :optional true}
    ;; FIXME: we need to also account for numbers
    [:map-of :keyword :string]]

   [:env_command
    {:description "Shell command to run that provides env vars for the service - usually this would be your secret manager of choice"
     :optional true}
    :string]

   [:env_file
    {:description "Path to a file to load environment variables from for the service. Can be absolute or relative  to :dir if specified, otherwise  relative to config root"
     :optional true}
    :string]])

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
      :string]]]

   [:services
    [:every #'Service]]])

(defn verify! [conf]
  (when-not (m/validate ServiceConfig conf)
    (errors/raise! ::errors/invalid-config (fn []
                                             (log/with-context {:service "config-parser"}
                                               (log/error "invalid config!"))
                                             (-> (m/explain ServiceConfig conf)
                                                 me/humanize
                                                 (yaml/generate-string)
                                                 println))))
  conf)

(defn- resolve-service-dir [{:keys [dir] :as svc}
                            {:keys [config-file-dir]}]
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

      ;; we have a dir, it's relative - resolve it
      (and (not-empty dir) (not (fs/absolute? dir)))
      (let [final-path (-> (str config-file-dir "/" dir)
                           fs/absolutize
                           fs/normalize)]
        (log/debugf "Service has a relative working directory; resolved to: %s" final-path)
        (if (fs/exists? final-path)
          (assoc svc :dir (str final-path))
          (errors/raise! ::errors/service-dir-doesnt-exist
                         #(log/errorf "FATAL: service specifies a working directory that doesn't exist: %s" final-path)))))))

(defn parse-env-file [env-file-path]
  (->> (slurp env-file-path)
       str/split-lines
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (map (fn [line]
              (let [[k v] (str/split (str/replace line #"export\s+" "") #"=" 2)]
                [k v])))
       (into {})))

(defn resolve-env [svc {:keys [config-file-dir]}]
  (if-let [env-file (:env_file svc)]
    (let [resolved-path (if (fs/absolute? env-file)
                          env-file
                          (str (fs/absolutize (fs/normalize (str config-file-dir "/" env-file)))))]
      (if (fs/exists? resolved-path)
        (update svc :env merge (parse-env-file resolved-path))
        (errors/raise! ::errors/service-env-file-doesnt-exist
                       #(log/errorf "FATAL: service '%s' has an env_file that doesn't exist: %s"
                                    (:name svc) resolved-path))))
    svc))

(defn resolve
  "Figures out other things post-structure validation:
  - resolves workdirs so that they're absolute path, with the config location being the root in case of relative paths
  - ensures that `depends_on` references existing services
  "
  [conf {:keys [config-file-dir]}]
  (-> conf
      (update :services (fn [services]
                          (->> services
                               (mapv (fn [svc]
                                       (-> svc
                                           (resolve-service-dir {:config-file-dir config-file-dir})
                                           (resolve-env {:config-file-dir config-file-dir})
                                           (update :tags set)))))))))

;; FIXME: why do we need to do this?
(def ordered-map-class
  (class (yaml/parse-string "foo: bar")))

(defn load! [conf-path]
  (when (str/blank? conf-path)
    (errors/raise! ::errors/config-not-found))
  (let [conf-path (str (fs/expand-home conf-path))
        _ (when-not (fs/exists? conf-path)
            (errors/raise! ::errors/config-not-found))
        config-file-dir (str (fs/normalize (fs/absolutize (fs/parent conf-path))))
        conf-data (->> (yaml/parse-string (slurp conf-path))
                       ;; convert ordered-map to regular maps, they're easier to work with
                       (walk/postwalk (fn [thing]
                                        (if (instance? ordered-map-class thing)
                                          (into {} thing)
                                          thing))))]
    (-> conf-data
        verify!
        (resolve {:config-file-dir config-file-dir}))))
