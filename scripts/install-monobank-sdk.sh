#!/usr/bin/env bash
# Installs the monobank SDK into the local Maven repository from a pinned commit, until 1.0.0 is on Maven Central.
# Remove this script (and its calls in .github/workflows) once pom.xml points at a released SDK version.
set -euo pipefail

SDK_REPO="https://github.com/damienmiheev/monobank-kotlin-sdk.git"
SDK_COMMIT="7238fed31a45315b32ed5522461f903f23e56849"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git -c advice.detachedHead=false clone --quiet --filter=blob:none "$SDK_REPO" "$work/sdk"
git -C "$work/sdk" -c advice.detachedHead=false checkout --quiet "$SDK_COMMIT"
(cd "$work/sdk" && ./gradlew --quiet --no-daemon publishToMavenLocal -x :relocation:publishToMavenLocal -x test)
echo "monobank SDK $SDK_COMMIT installed into ~/.m2"
