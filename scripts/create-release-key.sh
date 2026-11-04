#!/usr/bin/env bash
# Создаёт ключ подписи релизов и печатает команды для секретов GitHub.
# Ключ храните вне репозитория и сделайте резервную копию: без него обновления поверх установленной версии не встанут.
set -euo pipefail

KEYSTORE="${1:-$HOME/.dashphonic/release.jks}"
ALIAS="dashphonic"

if [ -e "$KEYSTORE" ]; then
  echo "$KEYSTORE already exists, refusing to overwrite it" >&2
  exit 1
fi
mkdir -p "$(dirname "$KEYSTORE")"
PASSWORD="$(openssl rand -base64 24 | tr -d '/+=')"

keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" -dname "CN=Dashphonic"
chmod 600 "$KEYSTORE"

cat <<INFO

Keystore: $KEYSTORE
Run these in the repository folder to add the secrets (gh must be logged in):

  base64 < "$KEYSTORE" | gh secret set DASHPHONIC_KEYSTORE_BASE64
  gh secret set DASHPHONIC_KEY_ALIAS --body "$ALIAS"
  gh secret set DASHPHONIC_KEYSTORE_PASSWORD --body "$PASSWORD"
  gh secret set DASHPHONIC_KEY_PASSWORD --body "$PASSWORD"

Save the password in a password manager. It is not stored anywhere else.
INFO
