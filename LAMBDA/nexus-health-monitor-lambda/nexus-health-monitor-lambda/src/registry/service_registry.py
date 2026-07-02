# src/registry/service_registry.py
"""
Complete registry of all 15 local plane services to monitor.

Criticality tiers drive alerting behavior:
  CRITICAL      → alert on first failure, page on-call immediately
  HIGH          → alert after 2 consecutive failures, ops team page
  STANDARD      → alert after N consecutive failures (configurable)
  INFRASTRUCTURE → alert but no immediate page

expected_components: Spring Boot Actuator components we expect to see.
  If listed but missing from response → DEGRADED (not just UP).

blocks_transactions: services that, if all down simultaneously,
  mean NO financial transactions can be processed.
  Drives the cascade failure scenario check.
"""

SERVICES: list[dict] = [

    # ── TIER 1: CRITICAL ───────────────────────────────────────
    {
        "name": "nexus-api-gateway",
        "port": 8080,
        "health_path": "/actuator/health",
        "criticality": "CRITICAL",
        "description": "Single entry point for all platform traffic",
        "expected_components": ["redis"],
        "max_response_ms": 1000,
        "blocks_transactions": True,
        "saga_participant": False,
    },
    {
        "name": "nexus-identity-service",
        "port": 8083,
        "health_path": "/actuator/health",
        "criticality": "CRITICAL",
        "description": "User authentication and JWT issuance",
        "expected_components": ["db", "redis"],
        "max_response_ms": 2000,
        "blocks_transactions": True,
        "saga_participant": True,
    },
    {
        "name": "nexus-account-service",
        "port": 8085,
        "health_path": "/actuator/health",
        "criticality": "CRITICAL",
        "description": "Account management and balance operations",
        "expected_components": ["db", "mongo", "redis"],
        "max_response_ms": 2000,
        "blocks_transactions": True,
        "saga_participant": True,
    },
    {
        "name": "nexus-transaction-service",
        "port": 8086,
        "health_path": "/actuator/health",
        "criticality": "CRITICAL",
        "description": "Core transaction processing engine",
        "expected_components": ["db", "kafka", "elasticsearch"],
        "max_response_ms": 3000,
        "blocks_transactions": True,
        "saga_participant": True,
    },

    # ── TIER 2: HIGH ───────────────────────────────────────────
    {
        "name": "nexus-fraud-service",
        "port": 8087,
        "health_path": "/actuator/health",
        "criticality": "HIGH",
        "description": "AI-powered real-time fraud detection",
        "expected_components": ["db", "redis"],
        "max_response_ms": 3000,
        "blocks_transactions": False,
        "saga_participant": True,
    },
    {
        "name": "nexus-ledger-service",
        "port": 8088,
        "health_path": "/actuator/health",
        "criticality": "HIGH",
        "description": "Double-entry bookkeeping",
        "expected_components": ["db", "mongo"],
        "max_response_ms": 2000,
        "blocks_transactions": False,
        "saga_participant": True,
    },
    {
        "name": "nexus-notification-service",
        "port": 8089,
        "health_path": "/actuator/health",
        "criticality": "HIGH",
        "description": "Multi-channel notification delivery",
        "expected_components": ["mongo", "kafka"],
        "max_response_ms": 2000,
        "blocks_transactions": False,
        "saga_participant": True,
    },
    {
        "name": "nexus-saga-orchestrator",
        "port": 8095,
        "health_path": "/actuator/health",
        "criticality": "HIGH",
        "description": "Distributed transaction SAGA coordinator",
        "expected_components": ["db", "kafka"],
        "max_response_ms": 2000,
        "blocks_transactions": True,
        "saga_participant": False,
    },

    # ── TIER 3: STANDARD ───────────────────────────────────────
    {
        "name": "nexus-ai-assistant-service",
        "port": 8090,
        "health_path": "/actuator/health",
        "criticality": "STANDARD",
        "description": "AI-powered financial assistant",
        "expected_components": ["db", "redis"],
        "max_response_ms": 5000,
        "blocks_transactions": False,
        "saga_participant": False,
    },
    {
        "name": "nexus-ai-kyc-service",
        "port": 8091,
        "health_path": "/actuator/health",
        "criticality": "STANDARD",
        "description": "AI identity document verification",
        "expected_components": ["mongo", "db"],
        "max_response_ms": 5000,
        "blocks_transactions": False,
        "saga_participant": True,
    },
    {
        "name": "nexus-analytics-service",
        "port": 8092,
        "health_path": "/actuator/health",
        "criticality": "STANDARD",
        "description": "Real-time financial analytics",
        "expected_components": ["elasticsearch", "redis"],
        "max_response_ms": 3000,
        "blocks_transactions": False,
        "saga_participant": False,
    },
    {
        "name": "nexus-risk-scoring-service",
        "port": 8094,
        "health_path": "/actuator/health",
        "criticality": "STANDARD",
        "description": "User behavioral and credit risk scoring",
        "expected_components": ["db", "redis"],
        "max_response_ms": 3000,
        "blocks_transactions": False,
        "saga_participant": False,
    },
    {
        "name": "nexus-audit-query-jvm",
        "port": 8097,
        "health_path": "/actuator/health",
        "criticality": "STANDARD",
        "description": "Immutable compliance audit trail (JVM query path)",
        "expected_components": ["elasticsearch", "kafka"],
        "max_response_ms": 3000,
        "blocks_transactions": False,
        "saga_participant": False,
    },
    {
        "name": "audit-write-native",
        "port": 8096,
        "health_path": "/q/health",
        "criticality": "STANDARD",
        "description": "High-throughput audit write path (Quarkus native)",
        "expected_components": [],
        "max_response_ms": 2000,
        "blocks_transactions": False,
        "saga_participant": False,
    },

    # ── TIER 4: INFRASTRUCTURE ─────────────────────────────────
    {
        "name": "nexus-config-service",
        "port": 8888,
        "health_path": "/actuator/health",
        "criticality": "INFRASTRUCTURE",
        "description": "Centralized configuration server (git-backed)",
        "expected_components": [],
        "max_response_ms": 1000,
        "blocks_transactions": False,
        "saga_participant": False,
    },
    {
        "name": "nexus-discovery-service",
        "port": 8761,
        "health_path": "/actuator/health",
        "criticality": "INFRASTRUCTURE",
        "description": "Eureka service registry",
        "expected_components": [],
        "max_response_ms": 1000,
        "blocks_transactions": False,
        "saga_participant": False,
    },
]

# Services that together enable financial transactions
TRANSACTION_BLOCKING_SERVICES: frozenset[str] = frozenset(
    svc["name"] for svc in SERVICES if svc["blocks_transactions"]
)

# Criticality → SNS topic type
ALERT_TOPIC_BY_CRITICALITY: dict[str, str] = {
    "CRITICAL":       "critical",
    "HIGH":           "standard",
    "STANDARD":       "standard",
    "INFRASTRUCTURE": "standard",
}

# Minimum consecutive failures before alerting (by criticality)
ALERT_THRESHOLD_BY_CRITICALITY: dict[str, int] = {
    "CRITICAL":       1,   # First failure = immediate page
    "HIGH":           2,   # Two failures before alerting
    "STANDARD":       2,   # Configurable, default 2
    "INFRASTRUCTURE": 3,   # More tolerant — infra restarts often
}