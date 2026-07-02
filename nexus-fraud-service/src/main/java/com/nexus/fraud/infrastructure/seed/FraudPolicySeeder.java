package com.nexus.fraud.infrastructure.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class FraudPolicySeeder implements ApplicationRunner {

    private final VectorStore policyVectorStore;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fraud_policy_embeddings", Long.class);
            if (count != null && count > 0) {
                log.info("fraud_policy_embeddings already has {} rows — skipping seed", count);
                return;
            }
            log.info("Seeding fraud_policy_embeddings...");
            policyVectorStore.add(buildPolicyDocuments());
            log.info("fraud_policy_embeddings seeded successfully");
        } catch (Exception e) {
            log.warn("Could not seed fraud_policy_embeddings (OpenAI key missing?): {}", e.getMessage());
        }
    }

    private List<Document> buildPolicyDocuments() {
        return List.of(
            doc("CNBV-VEL-001", "CNBV Velocity Rule — Transaction Frequency",
                "Section 4.1: No more than 5 transactions may be approved within a 5-minute " +
                "window for a single account. Transactions 6 and beyond must be flagged for " +
                "review. Applies to TRANSFER and PAYMENT types. Source: Circular CNBV 4/2019.",
                Map.of("policy_id", "CNBV-VEL-001", "section", "4.1", "category", "velocity")),

            doc("CNBV-AML-001", "AML Large Transaction Threshold",
                "Section 3.2: Any single transaction exceeding MXN 10,000 must be flagged " +
                "for enhanced due diligence (EDD). Transactions over MXN 50,000 require " +
                "immediate SAR consideration. Applies to all transaction types. Source: LFPIORPI Article 17.",
                Map.of("policy_id", "CNBV-AML-001", "section", "3.2", "category", "aml")),

            doc("CNBV-AML-002", "AML Structuring Detection",
                "Section 3.5: Multiple transactions below MXN 10,000 that collectively " +
                "exceed the threshold within a 24-hour period shall be treated as structured " +
                "transactions (smurfing). Three or more transactions between MXN 7,000–9,999 " +
                "in one day must be flagged. Source: LFPIORPI Article 18.",
                Map.of("policy_id", "CNBV-AML-002", "section", "3.5", "category", "aml")),

            doc("NEXUS-BL-001", "Sanctioned Entity Blacklist Policy",
                "Section 2.1: Any transaction involving a counterparty present on the OFAC SDN " +
                "list, CNBV blocked entities list, or Nexus internal blacklist must be automatically " +
                "REJECTED with code BL-001. Do not approve under any circumstances. Merchants " +
                "with MCC codes 7995 (gambling) or 5912 (pharmacy) in high-risk jurisdictions are " +
                "also subject to enhanced review.",
                Map.of("policy_id", "NEXUS-BL-001", "section", "2.1", "category", "blacklist")),

            doc("NEXUS-GEO-001", "Geographic Anomaly — Impossible Travel",
                "Section 5.3: If a user performs a transaction from geographic location A and " +
                "within less than 2 hours performs another transaction from location B where " +
                "travel time exceeds 2 hours, this is classified as impossible_travel=true. " +
                "Risk score must be increased by minimum 50 points. Require MFA re-authentication.",
                Map.of("policy_id", "NEXUS-GEO-001", "section", "5.3", "category", "geolocation")),

            doc("NEXUS-MCC-001", "High-Risk Merchant Category Codes",
                "Section 6.1: The following MCC codes are classified as HIGH RISK and require " +
                "enhanced scrutiny: 7995 (gambling/betting), 6051 (money orders/currency), " +
                "7801 (internet gambling), 5912 (drug stores in restricted jurisdictions), " +
                "4829 (wire transfer money orders), 6211 (securities brokers). Transactions to " +
                "these merchants above MXN 5,000 require additional verification.",
                Map.of("policy_id", "NEXUS-MCC-001", "section", "6.1", "category", "merchant")),

            doc("NEXUS-DEV-001", "New Device + New Country Risk Policy",
                "Section 7.2: A transaction initiated from a device not previously seen " +
                "(unknown device fingerprint) combined with a country not in the user's " +
                "travel history adds 25 points to the risk score. Unknown device alone adds " +
                "10 points. VPN detected adds 15 points. Tor exit node adds 50 points.",
                Map.of("policy_id", "NEXUS-DEV-001", "section", "7.2", "category", "device")),

            doc("NEXUS-NIGHT-001", "Nighttime High-Value Transaction Policy",
                "Section 8.1: Transactions exceeding MXN 20,000 initiated between 01:00 and " +
                "05:00 local time (America/Mexico_City) are subject to enhanced review. " +
                "This time window accounts for 73% of confirmed fraud cases in the Nexus " +
                "platform historical data. Add 10 risk points for nighttime high-value transactions.",
                Map.of("policy_id", "NEXUS-NIGHT-001", "section", "8.1", "category", "behavioral")),

            doc("NEXUS-NEW-001", "New Counterparty Risk Assessment",
                "Section 9.1: The first transaction to a counterparty account adds 10 base " +
                "risk points. If the counterparty account was created within the last 7 days, " +
                "add an additional 15 points. If the counterparty is in a different state " +
                "than the user's registered address, add 5 points. These are cumulative.",
                Map.of("policy_id", "NEXUS-NEW-001", "section", "9.1", "category", "counterparty")),

            doc("CNBV-SAR-001", "SAR Filing Requirements",
                "Section 11.1: A Suspicious Activity Report (SAR) must be filed with the " +
                "CNBV within 3 business days when: (a) fraud is confirmed, (b) risk score " +
                "exceeds 85 and transaction exceeds MXN 10,000, or (c) structuring is " +
                "detected. SAR must include transaction ID, user ID, amount, counterparty, " +
                "and specific suspicious indicators observed.",
                Map.of("policy_id", "CNBV-SAR-001", "section", "11.1", "category", "regulatory")),

            doc("NEXUS-MULE-001", "Money Mule Detection Pattern",
                "Section 10.3: Accounts that receive a large deposit and immediately transfer " +
                "90% or more to a third party within 30 minutes are classified as potential " +
                "money mule accounts. Pattern: large credit followed by immediate debit of " +
                "similar amount. This triggers automatic account freeze pending investigation.",
                Map.of("policy_id", "NEXUS-MULE-001", "section", "10.3", "category", "aml")),

            doc("NEXUS-DORMANT-001", "Dormant Account Reactivation Policy",
                "Section 12.2: Accounts with no activity for 90 or more days that suddenly " +
                "initiate a high-value transaction (over MXN 5,000) must undergo enhanced " +
                "verification. Risk score automatically increases by 20 points. Customer " +
                "contact verification required before approving transactions over MXN 15,000.",
                Map.of("policy_id", "NEXUS-DORMANT-001", "section", "12.2", "category", "behavioral")),

            doc("NEXUS-CBFX-001", "Cross-Border Transfer Limits",
                "Section 13.1: Transfers to international accounts (non-CLABE) are subject " +
                "to daily limits: MXN 25,000 per day per account. Monthly limit: MXN 100,000. " +
                "Transfers to FATF high-risk jurisdictions (list updated quarterly) require " +
                "compliance officer approval for amounts over MXN 10,000.",
                Map.of("policy_id", "NEXUS-CBFX-001", "section", "13.1", "category", "international")),

            doc("NEXUS-ZSCR-001", "Z-Score Amount Anomaly Detection",
                "Section 14.1: If a transaction amount exceeds the user's 90-day average " +
                "transaction amount by more than 3 standard deviations (Z-score > 3), " +
                "add 15 risk points. If Z-score > 5, add 30 risk points and flag for " +
                "mandatory review. Z-score calculation uses rolling 90-day window excluding " +
                "outliers already flagged as anomalous.",
                Map.of("policy_id", "NEXUS-ZSCR-001", "section", "14.1", "category", "behavioral")),

            doc("NEXUS-REL-001", "Established Relationship Risk Reduction",
                "Section 15.1: Transactions to counterparties with whom the user has " +
                "completed 10 or more successful prior transactions reduce the risk score " +
                "by 10 points. Transactions during typical user active hours (based on " +
                "historical pattern) reduce risk score by 5 points. Low-risk MCC category " +
                "(groceries, utilities, education) reduces risk score by 5 points.",
                Map.of("policy_id", "NEXUS-REL-001", "section", "15.1", "category", "mitigating"))
        );
    }

    private Document doc(String id, String title, String content, Map<String, Object> meta) {
        String docId = UUID.nameUUIDFromBytes(id.getBytes()).toString();
        return Document.builder()
                .id(docId)
                .text(title + "\n\n" + content)
                .metadata("policy_id", meta.get("policy_id"))
                .metadata("policy_title", title)
                .metadata("section", meta.get("section"))
                .metadata("category", meta.get("category"))
                .build();
    }
}
