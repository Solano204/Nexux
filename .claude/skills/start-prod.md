---
name: start-prod
description: Start the full NEXUS platform in production mode using docker-compose-prod.yml
---

Start the NEXUS platform in production Docker mode.

Steps:
1. Verify `.env` exists at the project root. If missing, STOP and tell the user — the platform cannot start without it.
2. Check that required secrets are present:
```bash
grep -c "POSTGRES_PASSWORD\|MONGO_PASSWORD\|REDIS_PASSWORD\|JWT_KEYSTORE_PASSWORD\|CONFIG_SERVER_PASSWORD\|GIT_TOKEN" .env
```
   Should return 6. If less, tell the user which vars are missing.
3. Verify `secrets/nexus-identity.jks` exists — identity service needs it.
4. Start infrastructure first (postgres, kafka, redis, elasticsearch, mongodb, zipkin, prometheus, grafana, kafdrop):
```bash
docker compose -f docker-compose-prod.yml up -d nexus-postgres nexus-mongodb nexus-redis nexus-kafka nexus-zookeeper nexus-elasticsearch nexus-zipkin nexus-prometheus nexus-grafana nexus-kafdrop
```
5. Wait for infrastructure to be healthy, then start platform services:
```bash
docker compose -f docker-compose-prod.yml up -d
```
6. Show container status:
```bash
docker compose -f docker-compose-prod.yml ps
```
7. Report any containers that are not in `running` or `healthy` state.

Notes:
- Startup order is managed by `depends_on` in docker-compose-prod.yml
- Full startup takes ~3-5 minutes due to healthcheck delays
- OPENAI_API_KEY is a placeholder — AI features won't work until replaced
- Grafana: http://localhost:3002 | Kafdrop: http://localhost:9003 | Eureka: http://localhost:8761
