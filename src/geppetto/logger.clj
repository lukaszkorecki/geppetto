(ns geppetto.logger
  (:require
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
