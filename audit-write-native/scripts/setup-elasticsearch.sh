#!/usr/bin/env bash
# scripts/setup-elasticsearch.sh
# Creates the nexus-audit-* index template with monthly rotation.

set -euo pipefail
ES_HOST="${ES_HOST:-http://localhost:9200}"

until curl -sf "${ES_HOST}/_cluster/health?wait_for_status=yellow&timeout=5s" > /dev/null; do
    echo "Waiting for Elasticsearch..."
    sleep 5
done

echo "Creating audit index template..."
curl -s -X PUT "${ES_HOST}/_index_template/nexus-audit-template" \
  -H "Content-Type: application/json" \
  -d '{
    "index_patterns": ["nexus-audit-*"],
    "template": {
      "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0
      },
      "mappings": {
        "properties": {
          "eventId":       { "type": "keyword" },
          "eventType":     { "type": "keyword" },
          "service":       { "type": "keyword" },
          "severity":      { "type": "keyword" },
          "userId":        { "type": "keyword" },
          "correlationId": { "type": "keyword" },
          "timestamp":     { "type": "date" },
          "payload":       { "type": "object", "enabled": false }
        }
      }
    },
    "data_stream": {}
  }' | jq .

echo "✅  Audit index template created"
