(ns geppetto.log)

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

(defn emit
  "Like print, but threadsafe"
  [{:keys [marker _pid line dev]}]
  (locking *out*
    (println (str (colorize marker) (format ".%s | %s " (name dev) line)))
    (flush)))
