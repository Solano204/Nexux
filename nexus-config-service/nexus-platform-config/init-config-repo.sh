#!/bin/bash
# nexus-platform-config/init-config-repo.sh
#
# Initializes the local bare Git repository that the config server
# uses as its backend. Run once during Docker Compose setup.
#
# In production: replace with a real GitHub/GitLab repository.

set -e

CONFIG_REPO_DIR="/config-repo"
CONFIG_SOURCE_DIR="/config-source"

echo "Initializing Nexus Platform Config Repository..."

# Initialize bare Git repo
if [ ! -d "$CONFIG_REPO_DIR/.git" ] && \
   [ ! -f "$CONFIG_REPO_DIR/HEAD" ]; then
    git init --bare "$CONFIG_REPO_DIR"
    echo "Bare Git repository initialized at $CONFIG_REPO_DIR"
fi

# Clone bare repo to a working copy
WORK_DIR=$(mktemp -d)
git clone "$CONFIG_REPO_DIR" "$WORK_DIR"

# Configure git identity
git -C "$WORK_DIR" config user.email "config@nexusplatform.local"
git -C "$WORK_DIR" config user.name "Nexus Config Bot"

# Copy all config files to working copy
cp "$CONFIG_SOURCE_DIR"/*.yml "$WORK_DIR/"

# Initial commit
git -C "$WORK_DIR" add .
git -C "$WORK_DIR" commit -m "Initial configuration for Nexus Financial Platform

Services included:
- nexus-api-gateway (8080)
- nexus-identity-service (8083)
- nexus-account-service (8085)
- nexus-transaction-service (8086)
- nexus-fraud-service (8087)
- nexus-ledger-service (8088)
- nexus-notification-service (8089)
- nexus-ai-assistant-service (8090)
- nexus-ai-kyc-service (8091)
- nexus-analytics-service (8092)
- nexus-risk-scoring-service (8094)
- nexus-saga-orchestrator (8095)
- nexus-audit-service (8096)
- nexus-discovery-service (8761)
"

# Push to bare repo
git -C "$WORK_DIR" push origin main

# Cleanup
rm -rf "$WORK_DIR"

echo "Config repository initialized successfully."
echo "Config server will serve from: $CONFIG_REPO_DIR"