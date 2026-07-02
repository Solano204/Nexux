IDENTITY
export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
cd ../nexus-identity-service
mvn clean package -DskipTests
ls -la target/nexus-identity-service-1.0.0.jar
unzip -p target/nexus-identity-service-1.0.0.jar META-INF/MANIFEST.MF




TRANSACTIONS
export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean package -DskipTests
docker compose -f docker-compose-prod.yml build nexus-transaction-service
docker compose -f docker-compose-prod.yml up -d nexus-transaction-service



FRAUD
cd nexus-fraud-service
export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"
mvn clean package -DskipTests
cd ..
docker compose -f docker-compose-prod.yml build nexus-fraud-service
docker compose -f docker-compose-prod.yml up -d nexus-fraud-service
docker compose -f docker-compose-prod.yml logs -f nexus-fraud-service



LEDGER
cd nexus-ledger-service

export JAVA_HOME="/c/Program Files/Java/jdk-25"
export PATH="$JAVA_HOME/bin:$PATH"

mvn clean package -DskipTests

cd ..
docker compose -f docker-compose-prod.yml build nexus-ledger-service
docker compose -f docker-compose-prod.yml up -d nexus-ledger-service
docker compose -f docker-compose-prod.yml logs -f nexus-ledger-service



DEBEZIUM (runs automatically on docker compose up)
# Kafka Connect starts with the compose file.
# nexus-debezium-init registers all 6 connectors on first run.
# 409 = connector already registered (safe, idempotent).

# Check connector status:
curl http://localhost:8083/connectors
curl http://localhost:8083/connectors/nexus-identity-outbox/status

# Re-register connectors if needed (e.g. after wiping volumes):
docker compose -f docker-compose-prod.yml restart nexus-debezium-init

# PostgreSQL WAL is enabled via docker-compose-prod.yml command flags.
# Replication slots created automatically by Debezium (one per DB):
#   debezium_identity, debezium_saga, debezium_accounts,
#   debezium_transactions, debezium_ledger, debezium_fraud

# IMPORTANT: if you drop and recreate volumes, connectors auto-re-register.
# Replication slots are stateful — if Kafka Connect stops unexpectedly,
# slots accumulate WAL until it reconnects (restart Connect to resume).