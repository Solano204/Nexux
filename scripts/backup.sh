#!/usr/bin/env bash
# Backup Postgres (all 10 databases + roles, via pg_dumpall) and MongoDB
# (all databases, via mongodump --archive) into timestamped, gzipped
# files with retention cleanup. Requires the nexus-postgres/nexus-mongodb
# containers to already be running (docker exec into them) — this script
# does NOT start, stop, or otherwise touch the stack itself.
#
# Usage:
#   ./scripts/backup.sh                     # backup now, 7-day retention
#   RETENTION_DAYS=14 ./scripts/backup.sh   # keep 14 days instead
#
# Restore:
#   Postgres: gunzip -c backups/postgres/<file>.sql.gz | docker exec -i nexus-postgres psql -U nexus
#   MongoDB:  gunzip -c backups/mongodb/<file>.archive.gz | docker exec -i nexus-mongodb mongorestore --username nexus --authenticationDatabase admin --archive
#
# Scheduling (Windows Task Scheduler — the cron equivalent used elsewhere
# in this repo per CLAUDE.md's Windows 11 + Git Bash environment):
#   schtasks /create /tn NexusBackup /sc daily /st 02:00 ^
#     /tr "\"C:\Program Files\Git\bin\bash.exe\" -c \"cd 'C:\Users\GAMER\Music\PROJECT IA STARTUP\NEXUS' && ./scripts/backup.sh\""
set -e

cd "$(dirname "$0")/.."

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RETENTION_DAYS="${RETENTION_DAYS:-7}"
BACKUP_DIR="backups"

mkdir -p "$BACKUP_DIR/postgres" "$BACKUP_DIR/mongodb"

if ! docker inspect nexus-postgres >/dev/null 2>&1; then
  echo "nexus-postgres container not found — is the stack running? Nothing to back up." >&2
  exit 1
fi

echo "── Postgres: pg_dumpall (all 10 databases + roles) ─────────────"
docker exec -i nexus-postgres pg_dumpall -U nexus \
  > "$BACKUP_DIR/postgres/nexus-postgres-${TIMESTAMP}.sql"
gzip "$BACKUP_DIR/postgres/nexus-postgres-${TIMESTAMP}.sql"
echo "  -> $BACKUP_DIR/postgres/nexus-postgres-${TIMESTAMP}.sql.gz"

if docker inspect nexus-mongodb >/dev/null 2>&1; then
  echo "── MongoDB: mongodump (all databases, archive+gzip) ────────────"
  MONGO_PASSWORD="$(cat secrets/mongo_password.txt 2>/dev/null || true)"
  if [ -z "$MONGO_PASSWORD" ]; then
    echo "  secrets/mongo_password.txt not found/empty — skipping MongoDB backup." >&2
    echo "  (See docker-compose-prod.yml's secrets: block for how to populate it.)" >&2
  else
    # --password on the CLI is briefly visible via `docker exec ... ps` for
    # the life of this one-off dump command — a much smaller exposure
    # window than the always-running services this repo already moved to
    # secret files, so left as-is rather than adding complexity here.
    docker exec -i nexus-mongodb mongodump \
      --username nexus --password "$MONGO_PASSWORD" --authenticationDatabase admin \
      --archive --gzip \
      > "$BACKUP_DIR/mongodb/nexus-mongodb-${TIMESTAMP}.archive.gz"
    echo "  -> $BACKUP_DIR/mongodb/nexus-mongodb-${TIMESTAMP}.archive.gz"
  fi
else
  echo "nexus-mongodb container not found — skipping MongoDB backup."
fi

echo ""
echo "── Retention: deleting backups older than ${RETENTION_DAYS} days ──"
find "$BACKUP_DIR/postgres" -name '*.sql.gz' -mtime "+${RETENTION_DAYS}" -print -delete
find "$BACKUP_DIR/mongodb" -name '*.archive.gz' -mtime "+${RETENTION_DAYS}" -print -delete

echo ""
echo "Done. $(ls "$BACKUP_DIR/postgres" 2>/dev/null | wc -l) postgres backup(s), $(ls "$BACKUP_DIR/mongodb" 2>/dev/null | wc -l) mongodb backup(s) on disk."
echo ""
echo "Elasticsearch / Kafka intentionally not covered here: both are"
echo "rebuildable from Postgres/Mongo (Debezium CDC replay repopulates ES"
echo "indices; Kafka topics are short-retention event streams, not a"
echo "system of record) rather than needing their own backup/restore path."
echo "If that assumption changes, add an ES snapshot API call and a Kafka"
echo "topic-dump step here."
