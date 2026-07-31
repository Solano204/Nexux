package com.nexus.identity.infrastructure.aws;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CognitoIdentityProviderException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidPasswordException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cognito User Mirror — keeps the "Option B" Cognito user pool in sync
 * with Docker-plane users (see AWS-DOCKER-WORKFLOWS/02_LOGIN_FLOW.md).
 *
 * Docker-plane Postgres remains the source of truth. Cognito is a mirror:
 * custom:userId ties a Cognito user back to its Postgres row, so
 * nexus-api-gateway can accept either token type and resolve the same
 * downstream identity (X-User-Id).
 *
 * Never allowed to fail the caller's transaction — registration and KYC
 * processing must succeed even if Cognito is unreachable or misconfigured.
 * All failures are logged and swallowed, same pattern as SqsKycPublisher.
 *
 * Circuit breaker (resiliencia guide, Fase 3): programmatic, not @CircuitBreaker
 * — every method already swallows its own exceptions to honor the
 * "never fails the caller" contract above, so an annotation would never see
 * a failure to react to (same reasoning as ai-assistant-service's clients).
 * No @Retry layered on top of this breaker: the AWS SDK already retries
 * transient errors internally before an exception ever reaches this class,
 * so adding Resilience4j retry here would be exactly the nested-retry
 * anti-pattern flagged in the Fase 2 audit. The breaker's value isn't
 * correctness (already safe) — it's skipping the SDK's own retry/timeout
 * cost entirely, on every call, once Cognito is confirmed down.
 */
@Slf4j
@Component
public class CognitoUserMirror {

    private final CognitoIdentityProviderClient cognitoClient;
    private final CircuitBreaker circuitBreaker;

    @Value("${nexus.aws.cognito-user-pool-id:}")
    private String userPoolId;

    @Value("${nexus.aws.cognito-client-id:}")
    private String clientId;

    public CognitoUserMirror(CognitoIdentityProviderClient cognitoClient,
                             CircuitBreakerRegistry circuitBreakerRegistry) {
        this.cognitoClient = cognitoClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("cognito");
    }

    /**
     * Cognito token pair returned alongside the local JWT on login.
     * Absent (Optional.empty()) whenever Cognito isn't configured, the
     * user has no Cognito mirror, or the call fails for any reason —
     * login always succeeds off Postgres regardless.
     */
    public record CognitoTokens(
            String accessToken,
            String idToken,
            String refreshToken,
            Integer expiresIn
    ) {}

    /**
     * Creates the mirrored Cognito user right after registration.
     * Uses the same plaintext password the user just submitted — this is
     * the only point in the flow it's available before being BCrypt-hashed
     * for Postgres, so "same email+password work on both planes" holds.
     *
     * Cognito's password policy (12+ chars, upper/lower/digit/symbol —
     * terraform/lambda-auth.tf) is stricter than the Docker-plane policy
     * (8+ chars, upper+digit — nexus-identity-service-prod.yml). A locally
     * valid password can fail AdminSetUserPassword; when that happens the
     * Cognito user still gets created but stays in FORCE_CHANGE_PASSWORD
     * state — logged clearly below, not silently pretended-successful.
     */
    public void mirrorNewUser(UUID userId, String email, String plaintextPassword) {
        if (userPoolId.isBlank()) {
            log.debug("Cognito user pool not configured, skipping mirror for userId={}", userId);
            return;
        }

        try {
            circuitBreaker.executeRunnable(() -> {
                cognitoClient.adminCreateUser(builder -> builder
                        .userPoolId(userPoolId)
                        .username(email)
                        .messageAction(MessageActionType.SUPPRESS)
                        .userAttributes(
                                AttributeType.builder().name("email").value(email).build(),
                                AttributeType.builder().name("email_verified").value("true").build(),
                                AttributeType.builder().name("custom:userId").value(userId.toString()).build(),
                                AttributeType.builder().name("custom:accountStatus").value("PENDING_KYC").build(),
                                AttributeType.builder().name("custom:kycVerified").value("false").build()
                        ));

                try {
                    cognitoClient.adminSetUserPassword(builder -> builder
                            .userPoolId(userPoolId)
                            .username(email)
                            .password(plaintextPassword)
                            .permanent(true));

                    log.info("Cognito user mirrored: userId={}", userId);

                } catch (InvalidPasswordException e) {
                    log.warn("Cognito user created but password does not satisfy the " +
                                    "pool's stricter policy (12+ chars, upper/lower/digit/symbol) — " +
                                    "userId={} left in FORCE_CHANGE_PASSWORD state: {}",
                            userId, e.getMessage());
                }
            });

        } catch (CallNotPermittedException e) {
            log.warn("Cognito circuit open, skipping user mirror: userId={}", userId);
        } catch (CognitoIdentityProviderException e) {
            log.error("Cognito user mirror failed: userId={} error={}",
                    userId, e.getMessage());
        }
    }

    /**
     * Keeps the Cognito mirror's KYC/account-status attributes current.
     * Called wherever the Docker plane changes a user's status
     * (KYC approval/rejection, suspension, etc.).
     */
    public void syncStatus(UUID userId, String email, String accountStatus, boolean kycVerified) {
        if (userPoolId.isBlank()) {
            log.debug("Cognito user pool not configured, skipping status sync for userId={}", userId);
            return;
        }

        try {
            circuitBreaker.executeRunnable(() ->
                    cognitoClient.adminUpdateUserAttributes(builder -> builder
                            .userPoolId(userPoolId)
                            .username(email)
                            .userAttributes(
                                    AttributeType.builder().name("custom:accountStatus").value(accountStatus).build(),
                                    AttributeType.builder().name("custom:kycVerified")
                                            .value(String.valueOf(kycVerified)).build()
                            )));

            log.info("Cognito status synced: userId={} accountStatus={} kycVerified={}",
                    userId, accountStatus, kycVerified);

        } catch (CallNotPermittedException e) {
            log.warn("Cognito circuit open, skipping status sync: userId={}", userId);
        } catch (CognitoIdentityProviderException e) {
            log.warn("Cognito status sync failed (mirror may be stale): userId={} error={}",
                    userId, e.getMessage());
        }
    }

    /**
     * Best-effort Cognito login, called alongside the (authoritative)
     * Postgres/BCrypt check. Uses plain InitiateAuth + USER_PASSWORD_AUTH
     * — deliberately NOT AdminInitiateAuth: USER_PASSWORD_AUTH is already
     * enabled on the app client (terraform/lambda-auth.tf explicit_auth_flows)
     * and needs no extra IAM permission, unlike the Admin* flow.
     *
     * Never blocks or fails the caller — Postgres remains the source of
     * truth for whether login succeeds at all. This only decides whether
     * a Cognito token pair rides along in the response.
     */
    public Optional<CognitoTokens> loginWithCognito(String email, String password) {
        if (userPoolId.isBlank() || clientId.isBlank()) {
            return Optional.empty();
        }

        try {
            var response = circuitBreaker.executeSupplier(() ->
                    cognitoClient.initiateAuth(builder -> builder
                            .clientId(clientId)
                            .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                            .authParameters(Map.of(
                                    "USERNAME", email,
                                    "PASSWORD", password
                            ))));

            AuthenticationResultType result = response.authenticationResult();
            if (result == null) {
                // Cognito wants an extra step (e.g. NEW_PASSWORD_REQUIRED after a
                // policy-rejected mirror password) instead of returning tokens.
                log.warn("Cognito login for {} returned a challenge instead of " +
                        "tokens ({}) — no Cognito token this round, local login unaffected",
                        maskEmail(email), response.challengeNameAsString());
                return Optional.empty();
            }

            return Optional.of(new CognitoTokens(
                    result.accessToken(),
                    result.idToken(),
                    result.refreshToken(),
                    result.expiresIn()));

        } catch (CallNotPermittedException e) {
            log.debug("Cognito circuit open, skipping login attempt: email={}", maskEmail(email));
            return Optional.empty();
        } catch (CognitoIdentityProviderException e) {
            log.warn("Cognito login failed (best-effort, local login unaffected): " +
                    "email={} error={}", maskEmail(email), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Best-effort Cognito-side session revocation, called alongside the
     * (authoritative) Redis blacklist on logout. AdminUserGlobalSignOut
     * invalidates every refresh token Cognito has issued this user —
     * doesn't require the client to have ever held or sent a Cognito
     * token, so logout stays a single call regardless of which token
     * type the client was actually using.
     *
     * Requires cognito-idp:AdminUserGlobalSignOut on the platform IAM
     * policy (terraform/iam.tf) — logs and no-ops if that's missing
     * rather than failing logout.
     */
    public void revokeSession(String email) {
        if (userPoolId.isBlank()) {
            return;
        }

        try {
            circuitBreaker.executeRunnable(() ->
                    cognitoClient.adminUserGlobalSignOut(builder -> builder
                            .userPoolId(userPoolId)
                            .username(email)));

            log.info("Cognito session revoked: email={}", maskEmail(email));

        } catch (CallNotPermittedException e) {
            log.debug("Cognito circuit open, skipping session revoke: email={}", maskEmail(email));
        } catch (CognitoIdentityProviderException e) {
            log.warn("Cognito revoke failed (best-effort, local logout unaffected): " +
                    "email={} error={}", maskEmail(email), e.getMessage());
        }
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
