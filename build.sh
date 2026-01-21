#!/usr/bin/env bash

rm -rf classes geppetto
mkdir -p classes
clojure -M -e "(compile 'geppetto.core)"

mkdir -p bin

native-image \
    -cp "$(clojure -Spath):classes" \
    -march=native \
    -H:Name=geppetto \
    -H:+ReportExceptionStackTraces \
    -H:+UnlockExperimentalVMOptions \
		-H:-AddAllFileSystemProviders \
    -H:IncludeResources="VERSION" \
    --features=clj_easy.graal_build_time.InitClojureClasses \
    --enable-url-protocols=http,https \
		--initialize-at-build-time \
    --verbose \
    --no-fallback \
    geppetto.core


mv ./geppetto ./bin/geppetto
