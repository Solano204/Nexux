//package com.nexus.identity.unit;
//
//import com.auth0.jwt.JWT;
//import com.auth0.jwt.algorithms.Algorithm;
//import com.nexus.identity.domain.model.User;
//import com.nexus.identity.domain.model.enums.UserStatus;
//import com.nexus.identity.infrastructure.jwt.JwtIssuer;
//import com.nexus.identity.infrastructure.jwt.JwtKeyManager;
//import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Tag;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.security.KeyPairGenerator;
//import java.security.interfaces.RSAPrivateKey;
//import java.security.interfaces.RSAPublicKey;
//import java.util.List;
//import java.util.UUID;
//
//import static org.assertj.core.api.Assertions.*;
//import static org.mockito.Mockito.*;
//
//@ExtendWith(MockitoExtension.class)
//@Tag("unit")
//class JwtIssuerTest {
//
//    @Mock
//    private JwtKeyManager keyManager;
//
//    private JwtIssuer jwtIssuer;
//    private RSAPublicKey publicKey;
//    private RSAPrivateKey privateKey;
//    private static final String TEST_KID = "test-key-001";
//
//    @BeforeEach
//    void setUp() throws Exception {
//        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
//        gen.initialize(2048);
//        var pair = gen.generateKeyPair();
//        publicKey = (RSAPublicKey) pair.getPublic();
//        privateKey = (RSAPrivateKey) pair.getPrivate();
//
//        when(keyManager.getCurrentKid()).thenReturn(TEST_KID);
//        when(keyManager.getCurrentPrivateKey()).thenReturn(privateKey);
//
//        jwtIssuer = new JwtIssuer(keyManager, new SimpleMeterRegistry());
//
//        // Inject values via reflection (normally set by @Value)
//        setField(jwtIssuer, "accessTokenExpirySeconds", 900L);
//        setField(jwtIssuer, "issuer", "nexus-identity-service");
//        setField(jwtIssuer, "audience", "nexus-platform");
//    }
//
//    @Test
//    @DisplayName("issueTokens: produces RS256-signed token with correct claims")
//    void issueTokens_activeUser_returnsSignedToken() {
//        User user = buildTestUser(UserStatus.ACTIVE);
//
//        JwtIssuer.TokenPair tokens = jwtIssuer.issueTokens(user, "device-fp");
//
//        assertThat(tokens.accessToken()).isNotBlank();
//        assertThat(tokens.refreshToken()).isNotBlank();
//        assertThat(tokens.jti()).isNotBlank();
//        assertThat(tokens.expiresAt()).isAfter(tokens.issuedAt());
//
//        // Verify token is valid RS256
//        var decoded = JWT.require(Algorithm.RSA256(publicKey, null))
//                .withIssuer("nexus-identity-service")
//                .withAudience("nexus-platform")
//                .build()
//                .verify(tokens.accessToken());
//
//        assertThat(decoded.getSubject())
//                .isEqualTo(user.getUserId().toString());
//        assertThat(decoded.getClaim("accountStatus").asString())
//                .isEqualTo("ACTIVE");
//        assertThat(decoded.getClaim("roles").asList(String.class))
//                .containsExactly("USER");
//        assertThat(decoded.getKeyId()).isEqualTo(TEST_KID);
//    }
//
//    @Test
//    @DisplayName("issueTokens: each call produces unique jti")
//    void issueTokens_calledTwice_differentJti() {
//        User user = buildTestUser(UserStatus.ACTIVE);
//
//        var tokens1 = jwtIssuer.issueTokens(user, "device-1");
//        var tokens2 = jwtIssuer.issueTokens(user, "device-2");
//
//        assertThat(tokens1.jti()).isNotEqualTo(tokens2.jti());
//        assertThat(tokens1.accessToken()).isNotEqualTo(tokens2.accessToken());
//    }
//
//    @Test
//    @DisplayName("issueTokens: refresh token is 256-bit URL-safe base64")
//    void issueTokens_refreshToken_isSecureRandom() {
//        User user = buildTestUser(UserStatus.ACTIVE);
//
//        var tokens = jwtIssuer.issueTokens(user, "device-fp");
//
//        // 32 bytes base64url-encoded (no padding) = ~43 chars
//        assertThat(tokens.refreshToken()).hasSizeGreaterThanOrEqualTo(40);
//        assertThat(tokens.refreshToken()).doesNotContain("+", "/", "=");
//    }
//
//    @Test
//    @DisplayName("refreshAccessToken: produces new token with new jti")
//    void refreshAccessToken_validUser_returnsNewToken() {
//        User user = buildTestUser(UserStatus.ACTIVE);
//        String oldJti = UUID.randomUUID().toString();
//
//        String newToken = jwtIssuer.refreshAccessToken(user, oldJti);
//
//        assertThat(newToken).isNotBlank();
//
//        var decoded = JWT.require(Algorithm.RSA256(publicKey, null))
//                .withIssuer("nexus-identity-service")
//                .build()
//                .verify(newToken);
//
//        // New jti must differ from old
//        assertThat(decoded.getId()).isNotEqualTo(oldJti);
//        assertThat(decoded.getSubject())
//                .isEqualTo(user.getUserId().toString());
//    }
//
//    @Test
//    @DisplayName("issueTokens: SUSPENDED accountStatus embedded in claims")
//    void issueTokens_suspendedUser_embedsSuspendedStatus() {
//        User user = buildTestUser(UserStatus.SUSPENDED);
//
//        var tokens = jwtIssuer.issueTokens(user, "device-fp");
//
//        var decoded = JWT.decode(tokens.accessToken());
//        assertThat(decoded.getClaim("accountStatus").asString())
//                .isEqualTo("SUSPENDED");
//    }
//
//    @Test
//    @DisplayName("issueTokens: kycVerified=false for PENDING_KYC users")
//    void issueTokens_pendingKycUser_kycVerifiedFalse() {
//        User user = buildTestUser(UserStatus.PENDING_KYC);
//
//        var tokens = jwtIssuer.issueTokens(user, "device-fp");
//
//        var decoded = JWT.decode(tokens.accessToken());
//        assertThat(decoded.getClaim("kycVerified").asBoolean()).isFalse();
//    }
//
//    // ── Helpers ───────────────────────────────────────────────
//
//    private User buildTestUser(UserStatus status) {
//        return User.builder()
//                .userId(UUID.randomUUID())
//                .email("test@nexusbank.com")
//                .fullName("Test User")
//                .status(status)
//                .roles(List.of("USER"))
//                .build();
//    }
//
//    private void setField(Object target, String fieldName,
//                          Object value) throws Exception {
//        var field = target.getClass().getDeclaredField(fieldName);
//        field.setAccessible(true);
//        field.set(target, value);
//    }
//}