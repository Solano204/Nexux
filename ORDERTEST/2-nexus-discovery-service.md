# 2 — nexus-discovery-service
**Port:** 8761 | **Status:** ALREADY RUNNING

## External Dependencies
- None — Eureka is fully in-memory

---

## Endpoint Testing Order

### 1. Health check
```
GET http://localhost:8761/actuator/health
```
Expected: `{"status":"UP"}`

> **Kafka topics:** none
> **DB affected:** none — in-memory Eureka registry

### 2. List all registered services
```
GET http://localhost:8761/eureka/apps
Accept: application/json
```
Expected: JSON with `applications` containing all registered services.
Use this to verify each service registered correctly after starting it.

> **Kafka topics:** none
> **DB affected:** none — reads in-memory Eureka service registry

### 3. Check a specific service registration
```
GET http://localhost:8761/eureka/apps/NEXUS-IDENTITY-SERVICE
Accept: application/json
```
Expected: Instance info with status `UP`

> **Kafka topics:** none
> **DB affected:** none — reads in-memory Eureka service registry

### 4. Eureka dashboard (browser)
```
GET http://localhost:8761/
```
Expected: HTML dashboard showing all registered instances

> **Kafka topics:** none
> **DB affected:** none — reads in-memory Eureka service registry

## Notes
- Check /eureka/apps after starting each service to confirm it registered
- If a service shows STARTING or DOWN, wait and retry — registration takes ~30s
- Service names in Eureka are uppercase of spring.application.name
