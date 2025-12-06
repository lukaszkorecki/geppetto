(ns geppetto.logger
  (:require
   [mokujin.logback :as lb]
   [mokujin.logback.config :as lbc]))

(set! *warn-on-reflection* true)

(def startup-sequence-logger
  (lbc/data->xml-str
   [:configuration
    [:appender {:name "STDOUT", :class "ch.qos.logback.core.ConsoleAppender"}
     [:withJansi true]
     [:encoder
      [:pattern "%highlight(%level) %X{event} %m%n"]]]
    [:root {:level "INFO"}
     [:appender-ref {:ref "STDOUT"}]]]))

(def runtime-logger-config
  (lbc/data->xml-str
   [:configuration
    [:appender {:name "STDOUT", :class "ch.qos.logback.core.ConsoleAppender"}
     [:withJansi true]
     [:encoder
      [:pattern "%-24.-24X{task}| %X{event} %m%n"]]]
    [:root {:level "INFO"}
     [:appender-ref {:ref "STDOUT"}]]]))

(def debug-logger-config
  (lbc/data->xml-str
   [:configuration
    [:appender {:name "STDOUT", :class "ch.qos.logback.core.ConsoleAppender"}
     [:withJansi true]
     [:encoder
      [:pattern "%p{%level %logger} %mdc log=%m%n"]]]
    [:root {:level "DEBUG"}
     [:appender-ref {:ref "STDOUT"}]]]))

(defn init! [{:keys [startup? debug?]}]
  (lb/configure! {:config (cond
                           startup? startup-sequence-logger
                           debug? debug-logger-config
                           :else
                           runtime-logger-config)})

  ;; not necessary... technically
  (if debug?
    (lb/set-level! :debug)
    (lb/set-level! :info)))

;; XXX: disabled for now!
(comment
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
    "Calculate hash and return ANSI color code string for task name"
    [name]
    (if @no-color?
      name
      (let [hash-code (mod (reduce + (map int name)) 6)
            color-code (get color-codes hash-code 37)]
        (str "\u001b[" color-code "m" name "\u001b[0m")))))
