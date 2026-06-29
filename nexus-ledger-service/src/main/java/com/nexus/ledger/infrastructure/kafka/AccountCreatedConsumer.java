package com.nexus.ledger.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexus.ledger.domain.model.ChartOfAccount;
import com.nexus.ledger.infrastructure.persistence.ChartOfAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Listens to accounts.created (published by nexus-account-service via Debezium outbox).
 * Registers each new user account in chart_of_accounts so the ledger
 * can post entries against it.
 *
 * Idempotent: if the account already exists the message is silently acked.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountCreatedConsumer {

    private final ChartOfAccountRepository coaRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "accounts.created",
            groupId = "ledger-service-account-events",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onAccountCreated(String message, Acknowledgment ack) {
        try {
            JsonNode event = objectMapper.readTree(message);

            UUID accountId = UUID.fromString(event.path("accountId").asText());

            // Idempotency — already registered
            if (coaRepository.findByAccountIdAndIsActiveTrue(accountId).isPresent()) {
                log.debug("COA entry already exists for accountId={}, skipping", accountId);
                ack.acknowledge();
                return;
            }

            String accountType = event.path("accountType").asText("CHECKING");
            String userId      = event.path("userId").asText(null);
            String currency    = event.path("currency").asText("MXN");
            BigDecimal opening = new BigDecimal(
                    event.path("initialBalance").asText("0.0000"));
            String accountNumber = event.path("accountNumber").asText(
                    "ACC-" + accountId.toString().substring(0, 8));

            ChartOfAccount coa = ChartOfAccount.builder()
                    .accountId(accountId)
                    .accountNumber(accountNumber)
                    .accountName(accountType + " Account")
                    .accountType(resolveAccountType(accountType))
                    .accountSubtype(accountType)
                    .normalBalance(resolveNormalBalance(accountType))
                    .currency(currency)
                    .isUserAccount(true)
                    .userId(userId != null ? UUID.fromString(userId) : null)
                    .openingBalance(opening)
                    .build();

            coaRepository.save(coa);

            log.info("COA entry created: accountId={} type={} userId={}",
                    accountId, accountType, userId);

            ack.acknowledge();

        } catch (Exception e) {
            log.error("Failed to process AccountCreatedEvent: {}", e.getMessage(), e);
            // Do not ack — Kafka will redeliver
        }
    }

    private String resolveAccountType(String accountType) {
        return switch (accountType.toUpperCase()) {
            case "CHECKING", "SAVINGS", "INVESTMENT" -> "ASSET";
            default -> "ASSET";
        };
    }

    private String resolveNormalBalance(String accountType) {
        return switch (accountType.toUpperCase()) {
            case "CHECKING", "SAVINGS", "INVESTMENT" -> "DEBIT";
            default -> "DEBIT";
        };
    }
}
