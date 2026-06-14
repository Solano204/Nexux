#!/usr/bin/env bash
# scripts/setup-elasticsearch.sh
#
# Creates the Elasticsearch index mapping for transaction search.
# Spring Data Elasticsearch auto-creates the index on startup
# (from @Document annotations), but this script lets you pre-create
# it with custom shards/replicas settings for production.
#
# Usage (local dev):
#   ./scripts/setup-elasticsearch.sh
#
# Usage (production):
#   ES_HOST=http://elastic:your_password@nexus-elasticsearch:9200 \
#     ./scripts/setup-elasticsearch.sh

set -euo pipefail

ES_HOST="${ES_HOST:-http://localhost:9200}"
INDEX_NAME="transactions"

echo "🔍  Setting up Elasticsearch index: ${INDEX_NAME}"
echo "    Host: ${ES_HOST}"

# Check Elasticsearch is up
until curl -sf "${ES_HOST}/_cluster/health?wait_for_status=yellow&timeout=5s" > /dev/null; do
    echo "    Waiting for Elasticsearch to be ready..."
    sleep 5
done

echo "    Elasticsearch is ready"

# Check if index already exists
HTTP_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "${ES_HOST}/${INDEX_NAME}")

if [ "$HTTP_STATUS" = "200" ]; then
    echo "    Index ${INDEX_NAME} already exists — skipping creation"
    exit 0
fi

# Create index with mapping
echo "    Creating index ${INDEX_NAME}..."
curl -s -X PUT "${ES_HOST}/${INDEX_NAME}" \
  -H "Content-Type: application/json" \
  -d '{
    "settings": {
      "number_of_shards": 1,
      "number_of_replicas": 0,
      "analysis": {
        "analyzer": {
          "transaction_analyzer": {
            "type": "custom",
            "tokenizer": "standard",
            "filter": ["lowercase", "stop"]
          }
        }
      }
    },
    "mappings": {
      "properties": {
        "transactionId":   { "type": "keyword" },
        "accountId":       { "type": "keyword" },
        "userId":          { "type": "keyword" },
        "amount":          { "type": "scaled_float", "scaling_factor": 100 },
        "currency":        { "type": "keyword" },
        "status":          { "type": "keyword" },
        "type":            { "type": "keyword" },
        "channel":         { "type": "keyword" },
        "merchantId":      { "type": "keyword" },
        "merchantName":    { "type": "text", "analyzer": "transaction_analyzer" },
        "description":     { "type": "text", "analyzer": "transaction_analyzer" },
        "createdAt":       { "type": "date", "format": "strict_date_optional_time" },
        "completedAt":     { "type": "date", "format": "strict_date_optional_time" }
      }
    }
  }' | jq .

echo ""
echo "✅  Elasticsearch index ${INDEX_NAME} created"
