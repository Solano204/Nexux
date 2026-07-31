package com.nexus.gateway.unit;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.nexus.gateway.jwt.CognitoJwksCache;
import com.nexus.gateway.jwt.CognitoJwtValidator;
import com.nexus.gateway.jwt.CompositeJwtValidator;
import com.nexus.gateway.jwt.JwtClaims;
import com.nexus.gateway.jwt.LocalJwtValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompositeJwtValidatorTest {

    @Mock private LocalJwtValidator localValidator;
    @Mock private CognitoJwtValidator cognitoValidator;
    @Mock private CognitoJwksCache cognitoJwksCache;

    private CompositeJwtValidator composite;
    private RSAPrivateKey privateKey;

    @BeforeEach
    void setUp() throws Exception {
        composite = new CompositeJwtValidator(localValidator, cognitoValidator, cognitoJwksCache);
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        privateKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
    }

    private String tokenWithIssuer(String issuer) {
        return JWT.create()
                .withIssuer(issuer)
                .withSubject("user-123")
                .sign(Algorithm.RSA256(null, privateKey));
    }

    @Test
    void routesLocalIssuerToLocalValidator() {
        String token = tokenWithIssuer("nexus-identity-service");
        JwtClaims claims = JwtClaims.builder().userId("user-123").build();
        when(localValidator.validate(token)).thenReturn(Mono.just(claims));

        StepVerifier.create(composite.validate(token))
                .expectNext(claims)
                .verifyComplete();

        verifyNoInteractions(cognitoValidator);
    }

    @Test
    void routesCognitoIssuerToCognitoValidatorWhenEnabled() {
        String issuer = "https://cognito-idp.us-east-1.amazonaws.com/pool-1";
        String token = tokenWithIssuer(issuer);
        JwtClaims claims = JwtClaims.builder().userId("user-456").build();
        when(cognitoJwksCache.isEnabled()).thenReturn(true);
        when(cognitoJwksCache.issuer()).thenReturn(issuer);
        when(cognitoValidator.validate(token)).thenReturn(Mono.just(claims));

        StepVerifier.create(composite.validate(token))
                .expectNext(claims)
                .verifyComplete();

        verifyNoInteractions(localValidator);
    }

    @Test
    void rejectsCognitoIssuerWhenCognitoDisabled() {
        String issuer = "https://cognito-idp.us-east-1.amazonaws.com/pool-1";
        String token = tokenWithIssuer(issuer);
        when(cognitoJwksCache.isEnabled()).thenReturn(false);

        StepVerifier.create(composite.validate(token)).verifyComplete();

        verifyNoInteractions(localValidator, cognitoValidator);
    }

    @Test
    void rejectsUnrecognizedIssuer() {
        String token = tokenWithIssuer("https://evil.example.com");
        when(cognitoJwksCache.isEnabled()).thenReturn(true);
        when(cognitoJwksCache.issuer()).thenReturn("https://cognito-idp.us-east-1.amazonaws.com/pool-1");

        StepVerifier.create(composite.validate(token)).verifyComplete();

        verifyNoInteractions(localValidator, cognitoValidator);
    }

    @Test
    void rejectsMalformedTokenWithoutThrowing() {
        StepVerifier.create(composite.validate("not-a-jwt-at-all"))
                .verifyComplete();

        verifyNoInteractions(localValidator, cognitoValidator);
    }
}
