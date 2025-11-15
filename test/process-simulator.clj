#!/usr/bin/env bb

(require '[clojure.tools.cli :refer [parse-opts]]
         '[org.httpkit.server :as http]
         '[cheshire.core :as json])

(def cli-options
  [["-e" "--exit-with EXIT_CODE" "Exit with specified exit code (default: 0)"
    :default 0
    :parse-fn #(Integer/parseInt %)
    :validate [#(and (>= % 0) (<= % 255)) "Must be between 0 and 255"]]
   
   ["-f" "--fail-after-ms MS" "Exit after specified milliseconds (never exit if not set)"
    :parse-fn #(Long/parseLong %)
    :validate [#(> % 0) "Must be positive"]]
   
   ["-p" "--[no-]print-output" "Print random numbers to stdout (default: true)"
    :default true]
   
   ["-i" "--print-interval-ms MS" "How often to print output in milliseconds (default: 100)"
    :default 100
    :parse-fn #(Long/parseLong %)
    :validate [#(> % 0) "Must be positive"]]
   
   ["-w" "--web-server-port PORT" "Boot in web-server mode on specified port"
    :parse-fn #(Integer/parseInt %)
    :validate [#(and (> % 0) (< % 65536)) "Must be a valid port number"]]
   
   ["-F" "--fail-health-check" "Make health check endpoint fail (default: false)"
    :default false]
   
   ["-h" "--help" "Show this help message"]])

(defn usage [options-summary]
  (->> ["Process Simulator Script - Simulates various process behaviors for dev process manager verification"
        ""
        "Usage: process-simulator.clj [options]"
        ""
        "Options:"
        options-summary
        ""
        "Examples:"
        "  # Simulate periodic output every 500ms and exit with code 1 after 5 seconds"
        "  process-simulator.clj --print-interval-ms 500 --fail-after-ms 5000 --exit-with 1"
        ""
        "  # Run simulated web server on port 8080 with failing health check behavior"
        "  process-simulator.clj --web-server-port 8080 --fail-health-check"
        ""
        "  # Simulate process printing output only, no web server"
        "  process-simulator.clj --print-output --print-interval-ms 100"]
       (clojure.string/join \newline)))

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (clojure.string/join \newline errors)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

;; Web server handlers
(defn health-handler [fail-health?]
  (fn [_req]
    (if fail-health?
      {:status 503
       :headers {"Content-Type" "text/plain"}
       :body "unhealthy"}
      {:status 200
       :headers {"Content-Type" "text/plain"}
       :body "live"})))

(defn output-handler [_req]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string {:rand-int (rand-int 10000)
                                 :timestamp (System/currentTimeMillis)})})

(defn router [fail-health?]
  (fn [req]
    (case (:uri req)
      "/health" ((health-handler fail-health?) req)
      "/output" (output-handler req)
      {:status 404
       :headers {"Content-Type" "text/plain"}
       :body "Not Found"})))

;; Main simulation logic
(defn print-output-loop [opts start-time]
  ;; Simulates a process producing periodic output, optionally exiting after a duration.
  (let [{:keys [print-output print-interval-ms fail-after-ms exit-with]} opts]
    (loop []
      (when print-output
        (printf "%s - %s - %s\n" 
                (or (System/getenv "GP_ID") "no-id")
                (or (System/getenv "foo") "no-foo")
                (rand-int 10000))
        (flush))
      
      ;; Check if we should exit (simulate crash/success/failure)
      (when fail-after-ms
        (let [elapsed (- (System/currentTimeMillis) start-time)]
          (when (>= elapsed fail-after-ms)
            (println (format "Exiting after %dms with code %d" elapsed exit-with))
            (System/exit exit-with))))
      
      (Thread/sleep print-interval-ms)
      (recur))))

(defn run-web-server [opts]
  ;; Simulates a process in 'service' mode, responding to health/output endpoints.
  (let [{:keys [web-server-port fail-health-check fail-after-ms exit-with]} opts
        start-time (System/currentTimeMillis)
        server (http/run-server (router fail-health-check) {:port web-server-port})]
    
    (println (format "Web server started on port %d" web-server-port))
    (println (format "  Health endpoint: http://localhost:%d/health (status: %s)"
                     web-server-port
                     (if fail-health-check "failing" "healthy")))
    (println (format "  Output endpoint: http://localhost:%d/output" web-server-port))
    
    ;; Simulate process lifetime (runs then exits or blocks forever)
    (if fail-after-ms
      (do
        (println (format "Server will exit after %dms with code %d" fail-after-ms exit-with))
        (Thread/sleep fail-after-ms)
        (server)
        (println (format "Exiting after %dms with code %d" fail-after-ms exit-with))
        (System/exit exit-with))
      (do
        (println "Press Ctrl-C to stop")
        ;; Block forever
        @(promise)))))

(defn -main [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    
    ;; Handle help and errors
    (cond
      (:help options)
      (exit 0 (usage summary))
      
      errors
      (exit 1 (error-msg errors)))
    
    (let [start-time (System/currentTimeMillis)]
      ;; Decide mode based on options
      (if (:web-server-port options)
        (run-web-server options)
        (print-output-loop options start-time)))))

;; Entry point: runs the simulator process with provided CLI args
(apply -main *command-line-args*)
