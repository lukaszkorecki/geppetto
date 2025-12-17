(ns geppetto.exit)

(def ^:dynamic *exit-fn*
  "A function that is called to exit the application.
  It takes one argument, the exit code.
  This can be rebound in tests to prevent the test process from exiting."
  #(System/exit %))

(defn exit!
  "Exits the application with the given exit code."
  [code]
  (*exit-fn* code))
