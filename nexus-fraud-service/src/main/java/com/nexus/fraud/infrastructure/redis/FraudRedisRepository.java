package com.nexus.fraud.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Duration;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FraudRedisRepository {

    private final StringRedisTemplate redisTemplate;

    public boolean isMerchantBlacklisted(String merchantId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet()
                .isMember("fraud:merchant:blacklist", merchantId));
    }

    public boolean isAccountFlagged(String accountId) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet()
                .isMember("fraud:account:flagged", accountId));
    }

    public void flagAccount(String accountId) {
        redisTemplate.opsForSet()
                .add("fraud:account:flagged", accountId);
    }

    public void blacklistMerchant(String merchantId) {
        redisTemplate.opsForSet()
                .add("fraud:merchant:blacklist", merchantId);
    }

    /**
     * ✅ ADD THIS METHOD TO RESOLVE THE MISSING SYMBOL SYNC BREAK
     * Removes a merchant from the active Redis block list.
     */
    public void removeFromBlacklist(String merchantId) {
        redisTemplate.opsForSet()
                .remove("fraud:merchant:blacklist", merchantId);
    }

    public void addRecentDecision(String userId, String transactionId,
                                  String outcome, BigDecimal riskScore) {
        String entry = transactionId + ":" + outcome + ":" +
                riskScore.toPlainString() + ":" +
                java.time.Instant.now().toString();
        String key = "fraud:user:recent:" + userId;
        redisTemplate.opsForList().rightPush(key, entry);
        redisTemplate.opsForList().trim(key, -10, -1); // Keep last 10
        redisTemplate.expire(key, Duration.ofHours(24));
    }
}