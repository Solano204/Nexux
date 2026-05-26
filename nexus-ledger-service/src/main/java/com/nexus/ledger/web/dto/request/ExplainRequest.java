package com.nexus.ledger.web.dto.request;

public record ExplainRequest(
        String message,
        String sessionId
) {}