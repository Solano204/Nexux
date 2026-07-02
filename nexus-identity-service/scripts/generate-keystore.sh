#!/usr/bin/env bash
# scripts/generate-keystore.sh
#
# Generates the RSA-2048 KeyStore used by the Identity Service to sign JWTs.
#
# Run this ONCE before starting the service for the first time.
# The output file is mounted into the container at /app/keys/nexus-identity.jks
#
# Usage:
#   chmod +x scripts/generate-keystore.sh
#   ./scripts/generate-keystore.sh
#
# Environment:
#   KEYSTORE_PASSWORD  (default: dev_keystore_password)
#   KEYSTORE_ALIAS     (default: nexus-identity)
#   KEYS_DIR           (default: ./keys)
#
# Prerequisites: Java 11+ (keytool is bundled with the JDK)

set -euo pipefail

KEYSTORE_PASSWORD="${KEYSTORE_PASSWORD:-dev_keystore_password}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-nexus-identity}"
KEYS_DIR="${KEYS_DIR:-./keys}"
KEYSTORE_FILE="${KEYS_DIR}/nexus-identity.jks"

echo "🔑  Generating JWT KeyStore for nexus-identity-service"
echo "    Output: ${KEYSTORE_FILE}"
echo "    Alias:  ${KEYSTORE_ALIAS}"
echo ""

# Create keys directory
mkdir -p "${KEYS_DIR}"

# Check if keystore already exists
if [ -f "${KEYSTORE_FILE}" ]; then
    echo "⚠️  KeyStore already exists at ${KEYSTORE_FILE}"
    echo "    Delete it manually if you want to regenerate."
    exit 0
fi

# Check keytool is available
if ! command -v keytool &> /dev/null; then
    echo "❌  keytool not found. Install a JDK (not JRE)."
    echo "    On macOS: brew install --cask temurin"
    echo "    On Ubuntu: sudo apt install openjdk-25-jdk"
    exit 1
fi

# Generate RSA-2048 KeyStore
keytool \
    -genkeypair \
    -alias "${KEYSTORE_ALIAS}" \
    -keyalg RSA \
    -keysize 2048 \
    -sigalg SHA256withRSA \
    -validity 3650 \
    -keystore "${KEYSTORE_FILE}" \
    -storepass "${KEYSTORE_PASSWORD}" \
    -keypass "${KEYSTORE_PASSWORD}" \
    -dname "CN=nexus-identity-service, OU=Platform, O=Nexus Financial, L=CDMX, ST=CDMX, C=MX" \
    -v

# Verify
echo ""
echo "✅  KeyStore generated successfully: ${KEYSTORE_FILE}"
echo ""
echo "    Verifying contents:"
keytool \
    -list \
    -v \
    -keystore "${KEYSTORE_FILE}" \
    -storepass "${KEYSTORE_PASSWORD}" \
    -alias "${KEYSTORE_ALIAS}" \
    2>&1 | grep -E "Alias|Owner|Valid from|Entry type|Algorithm"

echo ""
echo "⚠️  IMPORTANT — PRODUCTION:"
echo "    1. Generate a separate keystore with a strong password (not the default)"
echo "    2. Store the .jks file and password in a secrets manager"
echo "    3. Never commit the .jks file to Git"
echo "    4. Add keys/ to your .gitignore"
echo ""
echo "    To set a custom password:"
echo "      KEYSTORE_PASSWORD=your-secure-password ./scripts/generate-keystore.sh"
