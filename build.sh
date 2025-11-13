#!/usr/bin/env bash

rm -rf classes geppetto
mkdir -p classes
clojure -M -e "(compile 'geppetto.core)"


native-image \
    -cp "$(clojure -Spath):classes" \
    -H:Name=geppetto \
    -H:+ReportExceptionStackTraces \
    --features=clj_easy.graal_build_time.InitClojureClasses \
    --verbose \
    --no-fallback \
    geppetto.core
