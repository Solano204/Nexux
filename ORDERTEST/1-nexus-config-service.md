# 1 — nexus-config-service
**Port:** 8888 | **Status:** ALREADY RUNNING

## External Dependencies
- Local Git repo at `nexus-config-service/nexus-platform-config/` — config source
- No database, no Redis, no Kafka

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8888/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** none — reads from local Git repo, no external DB

### 2. Verify config is serving (example: fraud service dev profile)
```
GET http://localhost:8888/nexus-fraud-service/dev
Authorization: Basic nexus-config nexus-config-password
```
Expected: JSON with `propertySources` array

> **Kafka topics:** none
> **DB affected:**
> - Local Git repo `nexus-config-service/nexus-platform-config/nexus-fraud-service-dev.yml` — file read

### 3. Check all registered profiles (optional)
```
GET http://localhost:8888/nexus-api-gateway/dev
Authorization: Basic nexus-config nexus-config-password
```

> **Kafka topics:** none
> **DB affected:**
> - Local Git repo `nexus-config-service/nexus-platform-config/nexus-api-gateway-dev.yml` — file read

## Notes
- Basic auth: `nexus-config` / `nexus-config-password` (from CONFIG_SERVER_PASSWORD in .env)
- If this returns 401, check CONFIG_SERVER_PASSWORD in your .env
- All other services pull config from here on startup — if this is wrong, nothing works
