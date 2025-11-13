#!/usr/bin/env bb

;; TODO: make this better to test various scenarios:
;; - colorized output
;; - http server
;; - err logging
;; - working dir

(while true
  (printf "%s - %s - %s\n" (System/getenv "GP_ID") (System/getenv "foo") (rand-int 100))
  (flush)

  (when (rand-nth [true false false false false false false false false false])
    (binding [*out* *err*]
      (println "This is an error message!\nLeverage agile frameworks to provide a robust synopsis for high level overviews. Iterative approaches to corporate strategy foster collaborative thinking to further the overall value proposition. Organically grow the holistic world view of disruptive innovation via workplace diversity and empowerment.")
      (flush)))

  (Thread/sleep 500))
