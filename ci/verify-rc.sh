#!/usr/bin/env bash
# Checks a release candidate (RC) APK before build.yml promotes it to a release:
#   ci/verify-rc.sh <apk> <versionName> <versionCode> <sha256>
# The app id is com.elad.faceblur (not the beta's), the version is the release's, the native code
# is arm64-v8a only, it is signed by the release key (one signer, the pinned certificate) and its
# bytes are the ones the beta's release recorded. Exit 0 only if all hold.
# aapt2 and apksigner come from the newest Android SDK build-tools, or from $AAPT2 / $APKSIGNER.
set -euo pipefail

# SHA-256 of the signing certificate in app/faceblur.jks (keytool -exportcert ... | sha256sum).
CERT_SHA256=d8a7cc0dab1725c83d419e49e064e6aedca969c7896524ffc0d07625d0dc83fd
PACKAGE=com.elad.faceblur

APK=${1:?apk}
WANT_NAME=${2:?versionName}
WANT_CODE=${3:?versionCode}
WANT_SHA=${4:?sha256}

bad() { echo "verify-rc: $*" >&2; exit 1; }

tool() {
  local name=$1 dir
  for dir in $(find "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/nonexistent}}/build-tools" -mindepth 1 -maxdepth 1 -type d 2> /dev/null | sort -V -r); do
    [ -x "$dir/$name" ] && { echo "$dir/$name"; return 0; }
  done
  return 1
}
AAPT2=${AAPT2:-$(tool aapt2)} || bad "no aapt2 (Android SDK build-tools)"
APKSIGNER=${APKSIGNER:-$(tool apksigner)} || bad "no apksigner (Android SDK build-tools)"

[ -f "$APK" ] || bad "no file $APK"

SHA=$(sha256sum "$APK" | cut -d' ' -f1)
[ "$SHA" = "$WANT_SHA" ] || bad "sha256 $SHA, the beta recorded $WANT_SHA"

BADGING=$("$AAPT2" dump badging "$APK") || bad "aapt2 can't read $APK"
PKG_LINE=$(printf '%s\n' "$BADGING" | grep -m1 '^package: ') || bad "no package line"
field() { printf '%s\n' "$PKG_LINE" | sed -n "s/.* $1='\([^']*\)'.*/\1/p"; }
[ "$(field name)" = "$PACKAGE" ] || bad "package $(field name), wanted $PACKAGE"
[ "$(field versionName)" = "$WANT_NAME" ] || bad "versionName $(field versionName), wanted $WANT_NAME"
[ "$(field versionCode)" = "$WANT_CODE" ] || bad "versionCode $(field versionCode), wanted $WANT_CODE"
NATIVE=$(printf '%s\n' "$BADGING" | sed -n "s/^native-code: //p")
[ "$NATIVE" = "'arm64-v8a'" ] || bad "native code $NATIVE, wanted 'arm64-v8a' only"

CERTS=$("$APKSIGNER" verify --print-certs "$APK") || bad "apksigner: the signature doesn't verify"
DIGESTS=$(printf '%s\n' "$CERTS" | sed -n 's/^Signer #[0-9]* certificate SHA-256 digest: //p')
[ "$DIGESTS" = "$CERT_SHA256" ] || bad "signer certificate(s) ${DIGESTS//$'\n'/ }, wanted only $CERT_SHA256"

echo "verify-rc: $APK is $PACKAGE $WANT_NAME ($WANT_CODE), arm64-v8a, release key, sha256 $SHA"
