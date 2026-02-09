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
TARBALL="./bin/geppetto-macos-arm64.tar.gz"
FORMULA="./HomebrewFormula/geppetto.rb"

echo "Preparing release ${TAG}..."

if [[ ! -f "$BINARY" ]]; then
    echo "Error: Binary not found at $BINARY. Run 'make build-macos-arm64' first."
    exit 1
fi

if gh release view "$TAG" &>/dev/null; then
    echo "Error: Release $TAG already exists"
    exit 1
fi

# Create tarball for Homebrew
echo "Creating tarball..."
tar -czf "$TARBALL" -C bin geppetto

# Compute sha256
SHA256=$(shasum -a 256 "$TARBALL" | awk '{print $1}')
echo "SHA256: $SHA256"

# Update formula with new version and checksum
echo "Updating Homebrew formula..."
sed -i '' "s/version \".*\"/version \"${VERSION}\"/" "$FORMULA"
sed -i '' "s/sha256 \".*\"/sha256 \"${SHA256}\"/" "$FORMULA"

# Create GitHub release with both raw binary and tarball
gh release create "$TAG" \
    --title "Geppetto ${VERSION}" \
    --generate-notes \
    "${BINARY}#geppetto-macos-arm64" \
    "${TARBALL}#geppetto-macos-arm64.tar.gz"

echo "Release ${TAG} created: $(gh release view "$TAG" --json url -q .url)"

# Commit updated formula
echo "Committing updated formula..."
git add "$FORMULA"
git commit -m "Update Homebrew formula for ${TAG}"
git push

echo "Done! Formula updated and pushed."
