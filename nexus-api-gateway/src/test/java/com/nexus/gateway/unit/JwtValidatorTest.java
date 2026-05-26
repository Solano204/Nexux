//package com.nexus.gateway.unit;
//
//import com.auth0.jwt.JWT;
//import com.auth0.jwt.algorithms.Algorithm;
//import com.nexus.gateway.jwt.JwksCache;
//import com.nexus.gateway.jwt.JwtClaims;
//import com.nexus.gateway.jwt.JwtValidator;
//import io.micrometer.observation.ObservationRegistry;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Tag;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.InjectMocks;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//import reactor.test.StepVerifier;
//
//import java.security.KeyPairGenerator;
//import java.security.interfaces.RSAPrivateKey;
//import java.security.interfaces.RSAPublicKey;
//import java.time.Instant;
//import java.util.Date;
//import java.util.List;
//import java.util.UUID;
//
//import static org.mockito.ArgumentMatchers.anyString;
//import static org.mockito.Mockito.when;
//
//@ExtendWith(MockitoExtension.class)
//@Tag("unit")
//class JwtValidatorTest {
//
//    @Mock
//    JwksCache jwksCache;
//
//    @Mock
//    ObservationRegistry observationRegistry;
//
//    @InjectMocks
//    JwtValidator jwtValidator;
//
//    private RSAPublicKey publicKey;
//    private RSAPrivateKey privateKey;
//
//    @BeforeEach
//    void setUp() throws Exception {
//        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
//        gen.initialize(2048);
//        var pair = gen.generateKeyPair();
//        publicKey = (RSAPublicKey) pair.getPublic();
//        privateKey = (RSAPrivateKey) pair.getPrivate();
//    }
//
//    @Test
//    @DisplayName("Valid RS256 JWT returns populated JwtClaims")
//    void validate_validToken_returnsClaimsSuccessfully() {
//        String jti = UUID.randomUUID().toString();
//        String userId = UUID.randomUUID().toString();
//        String token = buildValidToken(userId, jti, List.of("USER"),
//                "ACTIVE", 3600);
//
//        when(jwksCache.getPublicKey(anyString())).thenReturn(publicKey);
//
//        StepVerifier.create(jwtValidator.validate(token))
//                .assertNext(claims -> {
//                    assert claims.userId().equals(userId);
//                    assert claims.jti().equals(jti);
//                    assert claims.roles().contains("USER");
//                    assert claims.accountStatus().equals("ACTIVE");
//                })
//                .verifyComplete();
//    }
//
//    @Test
//    @DisplayName("Expired JWT returns empty Mono")
//    void validate_expiredToken_returnsEmpty() {
//        String token = buildValidToken("user1", UUID.randomUUID().toString(),
//                List.of("USER"), "ACTIVE", -3600); // expired 1 hour ago
//
//        when(jwksCache.getPublicKey(anyString())).thenReturn(publicKey);
//
//        StepVerifier.create(jwtValidator.validate(token))
//                .verifyComplete(); // empty
//    }
//
//    @Test
//    @DisplayName("Token with alg=none is rejected")
//    void validate_noneAlgorithmToken_returnsEmpty() {
//        // Build a token with no signature (alg=none attack)
//        String unsignedToken = JWT.create()
//                .withSubject("user1")
//                .withJWTId(UUID.randomUUID().toString())
//                .withIssuer("nexus-platform")
//                .withAudience("nexus-platform")
//                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
//                .sign(Algorithm.none());
//
//        StepVerifier.create(jwtValidator.validate(unsignedToken))
//                .verifyComplete(); // rejected — empty
//    }
//
//    @Test
//    @DisplayName("Token with invalid signature is rejected")
//    void validate_invalidSignature_returnsEmpty() throws Exception {
//        // Create a different key pair to sign with
//        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
//        gen.initialize(2048);
//        var otherPair = gen.generateKeyPair();
//        RSAPrivateKey otherPrivateKey = (RSAPrivateKey) otherPair.getPrivate();
//
//        String token = JWT.create()
//                .withSubject("user1")
//                .withJWTId(UUID.randomUUID().toString())
//                .withIssuer("nexus-platform")
//                .withAudience("nexus-platform")
//                .withKeyId("test-kid")
//                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
//                .sign(Algorithm.RSA256(null, otherPrivateKey));
//
//        when(jwksCache.getPublicKey("test-kid")).thenReturn(publicKey);
//
//        StepVerifier.create(jwtValidator.validate(token))
//                .verifyComplete(); // rejected — empty
//    }
//
//    @Test
//    @DisplayName("Token with SUSPENDED accountStatus returns valid claims")
//    void validate_suspendedAccount_returnsClaims() {
//        String token = buildValidToken("user1", UUID.randomUUID().toString(),
//                List.of("USER"), "SUSPENDED", 3600);
//
//        when(jwksCache.getPublicKey(anyString())).thenReturn(publicKey);
//
//        StepVerifier.create(jwtValidator.validate(token))
//                .assertNext(claims -> {
//                    assert claims.isAccountSuspended();
//                    assert !claims.isAccountActive();
//                })
//                .verifyComplete();
//    }
//
//    @Test
//    @DisplayName("Token missing subject claim is rejected")
//    void validate_missingSubject_returnsEmpty() {
//        String token = JWT.create()
//                // No .withSubject()
//                .withJWTId(UUID.randomUUID().toString())
//                .withIssuer("nexus-platform")
//                .withAudience("nexus-platform")
//                .withKeyId("test-kid")
//                .withClaim("roles", List.of("USER"))
//                .withClaim("accountStatus", "ACTIVE")
//                .withExpiresAt(Date.from(Instant.now().plusSeconds(3600)))
//                .sign(Algorithm.RSA256(null, privateKey));
//
//        when(jwksCache.getPublicKey("test-kid")).thenReturn(publicKey);
//
//        StepVerifier.create(jwtValidator.validate(token))
//                .verifyComplete(); // rejected
//    }
//
//    private String buildValidToken(String userId, String jti,
//                                   List<String> roles, String accountStatus,
//                                   int expiryOffsetSeconds) {
//        return JWT.create()
//                .withSubject(userId)
//                .withJWTId(jti)
//                .withIssuer("nexus-platform")
//                .withAudience("nexus-platform")
//                .withKeyId("test-kid-001")
//                .withClaim("roles", roles)
//                .withClaim("accountStatus", accountStatus)
//                .withIssuedAt(Date.from(Instant.now()))
//                .withExpiresAt(
//                        Date.from(Instant.now().plusSeconds(expiryOffsetSeconds)))
//                .sign(Algorithm.RSA256(null, privateKey));
//    }
//}