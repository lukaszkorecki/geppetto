(ns geppetto.logger
  (:require
   [mokujin.logback :as lb]
   [mokujin.logback.config :as lbc]))

(set! *warn-on-reflection* true)

(lb/configure! {:config (lbc/data->xml-str
                         [:configuration
                          [:appender {:name "PLAIN_TEXT", :class "ch.qos.logback.core.ConsoleAppender"}
                           [:withJansi true]
                           [:encoder
                            [:pattern "%mdc %msg%n"]]]

                          [:root {:level "INFO"}
                           [:appender-ref {:ref "PLAIN_TEXT"}]]])})

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
      (str "\u001b[" color-code "m" name "\u001b[0m"))))
