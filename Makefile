fmt: # format the codebase
	clojure-lsp format

build-bin: build-macos-arm64 build-linux-amd64 build-linux-arm64 # build all binaries


build-macos-arm64: # build macos arm64 binary locally
	./build.sh

build-linux-amd64: # build linux amd64 binary in Docker
	echo 'no-op'

build-linux-arm64: # build linux arm64 binary in Docker
	echo 'no-op'

help:
	@awk '/^[a-z_\-]+:/ { print $$1 }' ./Makefile | sort
