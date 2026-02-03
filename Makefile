fmt: # format the codebase
	sed -i '' 's/[[:space:]]*$$//' */**/*.clj
	clojure-lsp format

test:
	@clojure -M:test

build-bin: build-macos-arm64 build-linux-amd64 build-linux-arm64 # build all binaries


build-macos-arm64: # build macos arm64 binary locally
	./build.sh

build-linux-amd64: # build linux amd64 binary in Docker
	echo 'no-op'

build-linux-arm64: # build linux arm64 binary in Docker
	echo 'no-op'

release: build-macos-arm64 # create a GitHub release
	./release.sh

help:
	@awk '/^[a-z_\-]+:/ { print $$1 }' ./Makefile | sort


.PHONY: fmt test build-bin build-macos-arm64 build-linux-amd64 build-linux-arm64 release help
