#!/usr/bin/env bash

rm -rf classes geppetto
mkdir -p classes
clojure -M -e "(compile 'geppetto.core)"

mkdir -p bin

native-image \
    -cp "$(clojure -Spath):classes" \
    -H:Name=geppetto \
    -H:+ReportExceptionStackTraces \
    -H:+UnlockExperimentalVMOptions \
		-H:-AddAllFileSystemProviders \
    --features=clj_easy.graal_build_time.InitClojureClasses \
		--initialize-at-build-time=ch.qos.logback.classic.Logger \
		--initialize-at-build-time=ch.qos.logback.classic.LoggerContext \
		--initialize-at-build-time=ch.qos.logback.core.spi.AppenderAttachableImpl \
		--initialize-at-build-time=ch.qos.logback.core.BasicStatusManager \
		--initialize-at-build-time=ch.qos.logback.core.util.COWArrayList \
		--initialize-at-build-time=ch.qos.logback.classic.util.LogbackMDCAdapter \
		--initialize-at-build-time=ch.qos.logback.classic.spi.TurboFilterList \
		--initialize-at-build-time=ch.qos.logback.classic.spi.LoggerContextVO \
		--initialize-at-build-time=ch.qos.logback.classic.Level \
		--initialize-at-build-time=ch.qos.logback.core.status.InfoStatus \
		--initialize-at-build-time=ch.qos.logback.core.ConsoleAppender \
		--initialize-at-build-time=ch.qos.logback.core.spi.LogbackLock \
		--initialize-at-build-time=ch.qos.logback.core.spi.FilterAttachableImpl \
		--initialize-at-build-time=ch.qos.logback.core.joran.spi.ConsoleTarget$1 \
		--initialize-at-build-time=ch.qos.logback.core.helpers.CyclicBuffer \
		--initialize-at-build-time=ch.qos.logback.core.encoder.LayoutWrappingEncoder \
		--initialize-at-build-time=ch.qos.logback.classic.util.ContextInitializer \
		--initialize-at-build-time=ch.qos.logback.core.spi.ContextAwareImpl \
		--initialize-at-build-time=ch.qos.logback.classic.util.ContextInitializer$1 \
		--initialize-at-build-time=ch.qos.logback.classic.layout.TTLLLayout \
		--initialize-at-build-time=ch.qos.logback.classic.BasicConfigurator \
		--initialize-at-build-time=ch.qos.logback.core.util.CachingDateFormatter \
		--initialize-at-build-time=ch.qos.logback.core.joran.spi.ConsoleTarget \
		--initialize-at-build-time=ch.qos.logback.classic.pattern.ThrowableProxyConverter \
		--initialize-at-build-time=ch.qos.logback.core \
		--initialize-at-build-time=ch.qos.logback.core.helpers \
		--initialize-at-build-time=ch.qos.logback.core.spi \
		--initialize-at-build-time=ch.qos.logback.classic \
		--initialize-at-build-time=ch.qos.logback.classic.layout \
		--initialize-at-build-time=ch.qos.logback.classic.pattern \
		--initialize-at-build-time=ch.qos.logback.classic.util \
		--initialize-at-build-time=ch.qos.logback.classic.spi \
    --initialize-at-build-time=ch.qos.logback.classic.model.processor.RootLoggerModelHandler \
    --initialize-at-build-time=org.xml.sax.helpers.LocatorImpl \
    --initialize-at-build-time=org.xml.sax.helpers.AttributesImpl \
    --verbose \
    --no-fallback \
    geppetto.core

mv ./geppetto ./bin/geppetto
