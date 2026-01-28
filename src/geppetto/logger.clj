(ns geppetto.logger
  (:require
   [cheshire.core :as json]
   [clj-yaml.core :as yaml]
   [clojure.string :as str]
   [mokujin.logback :as lb]
   [mokujin.logback.config :as lbc]))

(set! *warn-on-reflection* true)

(defn config [debug?]
  (lbc/data->xml-str
   [:configuration
    [:appender {:name "STDOUT", :class "ch.qos.logback.core.ConsoleAppender"}
     [:withJansi true]
     [:encoder
      [:pattern "%X{service} | %X{event} %m%n"]]]
    [:root {:level (if debug? "DEBUG" "INFO")}
     [:appender-ref {:ref "STDOUT"}]]]))

(defn init! [{:keys [debug?]}]
  (lb/configure! {:config (config debug?)})

  ;; not necessary... technically
  (if debug?
    (lb/set-level! :debug)
    (lb/set-level! :info)))

;; XXX: Need to think of way of supporting custom colorization
;;      on Logback level, see here: https://logback.qos.ch/manual/layouts.html#customConversionSpecifier
  ;; Task output logging
(def color-codes
  {0 31 ; red
   1 32 ; green
   2 33 ; yellow
   3 34 ; blue
   4 35 ; magenta
   5 36 ; cyan
   })

(def no-color?
  (delay (boolean (System/getenv "NO_COLOR"))))

(defn colorize
  "Calculate hash and return ANSI color code string for service name"
  [name]
  (if @no-color?
    name
    (let [hash-code (mod (reduce + (map int name)) 6)
          color-code (get color-codes hash-code 37)]
      (str "\u001b[" color-code "m" name "\u001b[0m"))))

;; Log line formatting

(defn- try-parse-json
  "Attempt to parse line as JSON. Returns parsed map or nil if not valid JSON."
  [line]
  (try
    (let [parsed (json/parse-string line)]
      ;; Only return if it's a map (typical structured log)
      (when (map? parsed)
        parsed))
    (catch Exception _
      nil)))

(defn- format-as-yaml
  "Format parsed JSON data as indented YAML block.
   Each line is prefixed with two spaces for visual indentation under service name."
  [data]
  (let [yaml-str (yaml/generate-string data :dumper-options {:flow-style :block})]
    ;; Indent each line and prepend newline so it starts on next line after service name
    (->> (str/split-lines yaml-str)
         (map #(str "  " %))
         (str/join "\n")
         (str "\n"))))

(defn format-line
  "Format a log line. If :parse-json? is true and line is valid JSON,
   return formatted YAML. Otherwise return line unchanged."
  [line {:keys [parse-json?]}]
  (if parse-json?
    (if-let [parsed (try-parse-json line)]
      (format-as-yaml parsed)
      line)
    line))
