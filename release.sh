#!/usr/bin/env bash
set -euo pipefail

VERSION_FILE="./resources/VERSION"
if [[ ! -f "$VERSION_FILE" ]]; then
    echo "Error: VERSION file not found at $VERSION_FILE"
    exit 1
fi

VERSION=$(cat "$VERSION_FILE" | tr -d '[:space:]')
TAG="v${VERSION}"
BINARY="./bin/geppetto"

echo "Preparing release ${TAG}..."

if [[ ! -f "$BINARY" ]]; then
    echo "Error: Binary not found at $BINARY. Run './build.sh' first."
    exit 1
fi

if gh release view "$TAG" &>/dev/null; then
    echo "Error: Release $TAG already exists"
    exit 1
fi

gh release create "$TAG" \
    --title "Geppetto ${VERSION}" \
    --generate-notes \
    "${BINARY}#geppetto-macos-arm64"

echo "Release ${TAG} created: $(gh release view "$TAG" --json url -q .url)"
