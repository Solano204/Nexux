package com.nexus.account.domain.event;
import java.util.UUID;
public record AccountClosedEvent(UUID accountId, String reason) {}