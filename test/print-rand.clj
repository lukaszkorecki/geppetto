#!/usr/bin/env bb

(def counter (atom 0))

(def die-after (rand-int 100))

(printf "Starting GP_ID=%s will die after %d iterations\n" (System/getenv "GP_ID") die-after)

(while true
  (printf "%s - %s - %s\n" (System/getenv "GP_ID") (System/getenv "foo") (rand-int 100))
  (swap! counter inc)

  (when (>= @counter die-after)
    (System/exit 1))

  (Thread/sleep (rand-int 1000)))
