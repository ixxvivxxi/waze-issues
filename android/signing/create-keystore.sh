#!/usr/bin/env bash
# Create a stable release keystore (run once). Keep a backup — losing it means users must reinstall.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
KEYSTORE="$DIR/waze-issues.keystore"
PROPS="$DIR/keystore.properties"

if [[ -f "$KEYSTORE" && -f "$PROPS" ]]; then
  echo "Already exists: $KEYSTORE"
  exit 0
fi

PASS="${WAZE_ISSUES_KEYSTORE_PASS:-waze-issues-release}"

keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -alias waze-issues \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -dname "CN=Waze Issues, OU=ster.by, O=ster, L=Minsk, C=BY"

cat > "$PROPS" << EOF
storeFile=waze-issues.keystore
storePassword=$PASS
keyAlias=waze-issues
keyPassword=$PASS
EOF

chmod 600 "$KEYSTORE" "$PROPS"
echo "Created $KEYSTORE"
echo "IMPORTANT: back up android/signing/ (keystore + properties). Do not commit them."
