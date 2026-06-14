#!/usr/bin/env bash
# scripts/pull-ollama-model.sh
#
# Pulls the mistral:7b model into the Ollama container.
# Run this ONCE after the first  docker compose up -d.
# The model is ~4GB and persists in the ollama-models Docker volume.
#
# Usage:
#   ./scripts/pull-ollama-model.sh
#
# Requirements:
#   - nexus-ollama container must be running
#   - ~4GB free disk space
#   - ~8GB RAM available for inference

set -euo pipefail

CONTAINER="${OLLAMA_CONTAINER:-nexus-ollama}"
MODEL="${OLLAMA_MODEL:-mistral:7b}"

echo "🤖  Pulling Ollama model: ${MODEL}"
echo "    Container: ${CONTAINER}"
echo "    Size: ~4GB download — this may take several minutes"
echo ""

# Wait for Ollama to be ready
until docker exec "${CONTAINER}" curl -sf http://localhost:11434/api/tags > /dev/null 2>&1; do
    echo "    Waiting for Ollama to be ready..."
    sleep 3
done
echo "    Ollama is ready"

# Check if model already exists
if docker exec "${CONTAINER}" ollama list 2>/dev/null | grep -q "${MODEL}"; then
    echo "    ✅ Model ${MODEL} already exists — skipping download"
    exit 0
fi

# Pull the model
echo "    Pulling ${MODEL}..."
docker exec "${CONTAINER}" ollama pull "${MODEL}"

echo ""
echo "✅  Model ${MODEL} pulled successfully"
echo ""
echo "    Verify it's loaded:"
docker exec "${CONTAINER}" ollama list
echo ""
echo "    Test inference:"
echo "      docker exec ${CONTAINER} ollama run ${MODEL} 'Hello, how are you?'"
