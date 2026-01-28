(ns geppetto.system
  (:require
   [clojure.set :as set]
   [com.stuartsierra.component :as component]
   [com.stuartsierra.dependency :as dep]
   [geppetto.errors :as errors]
   [geppetto.logger :as logger]
   [geppetto.service :as service]
   [geppetto.watchdog :as watchdog]))

(defn- resolve-dependencies
  "Given a list of services and a set of selected service names, returns a set of all service names
  including transitive dependencies. Uses Component's dependency library to resolve the graph."
  [all-services selected-names]
  ;; Build dependency graph from all services
  (let [dep-graph (reduce (fn [graph {:keys [name depends_on]}]
                            (reduce (fn [g' dep]
                                      (dep/depend g' name dep))
                                    graph
                                    depends_on))
                          (dep/graph)
                          all-services)
        ;; Get transitive dependencies (doesn't include the selected names themselves)
        dependencies (dep/transitive-dependencies-set dep-graph selected-names)]
    ;; Return both selected names and their dependencies
    (set/union selected-names dependencies)))

(defn build [{:keys [services exit-mode services-to-launch tags] :as _args}]
  {:pre [(set? services-to-launch)
         (set? tags)
         (vector? services)]}
  (if (empty? services)
    (errors/raise! ::errors/no-services-in-config)
    ;; filtering rules (Docker Compose compatible):
    ;; - services WITHOUT tags always run (like Compose services without profiles)
    ;; - services WITH tags only run when their profile/tag is explicitly enabled
    ;; - name filtering overrides tag filtering: explicitly named services always run
    (let [initially-filtered (->> services
                                  ;; first filter by name (takes priority)
                                  (filter (fn [{:keys [name] :as _svc}]
                                            (or
                                             ;; no name filter means apply tag filtering
                                             (empty? services-to-launch)
                                             ;; name filter active: include if in the set (bypasses tag check)
                                             (contains? services-to-launch name))))

                                  ;; then filter by tags (only if name filter wasn't applied)
                                  (filter (fn [{:keys [name] :as svc}]
                                            (or
                                             ;; explicitly named service bypasses tag filtering
                                             (contains? services-to-launch name)
                                             ;; no tags in service -> always include
                                             (empty? (:tags svc))
                                             ;; service has tags -> only include if one of its tags is active
                                             (and (seq (:tags svc))
                                                  (seq (set/intersection tags (:tags svc))))))))

          ;; Resolve dependencies: include all services that the initially filtered services depend on
          selected-names (set (map :name initially-filtered))
          all-required-names (resolve-dependencies services selected-names)

          ;; Filter the original service list to include only required services
          final-services-list (filter (fn [svc]
                                        (contains? all-required-names (:name svc)))
                                      services)

          longest-name-char-count (when (seq final-services-list)
                                    (apply max (map #(count (:name %)) final-services-list)))

          ;; Build all service components
          service-sys (->> final-services-list
                           (map (fn [{:keys [name depends_on] :as service-def}]
                                  (let [;; loggable name padded to longest service name for prettier logs
                                        loggable-name (str (logger/colorize name)
                                                           (apply str (repeat
                                                                       (- longest-name-char-count
                                                                          (count name))
                                                                       " ")))
                                        svc (service/create (assoc service-def :loggable-name loggable-name))
                                        dependencies (mapv keyword (seq depends_on))
                                        svc (component/using
                                             svc
                                             (vec (concat [] dependencies)))]
                                    (hash-map (keyword name) svc))))
                           (into {}))

          ;; Add watchdog that depends on all services
          service-sys (assoc service-sys :watchdog (component/using
                                                    (watchdog/create {:exit-mode exit-mode})
                                                    (mapv keyword (keys service-sys))))]
      (component/map->SystemMap service-sys))))
