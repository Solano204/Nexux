package com.nexus.identity.unit;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.nexus.identity.application.command.UserCommandService;
import com.nexus.identity.infrastructure.jwt.JwksEndpointProvider;
import com.nexus.identity.web.controller.AuthController;
import com.nexus.identity.web.dto.request.LoginRequest;
import com.nexus.identity.web.dto.request.PasswordResetRequest;
import com.nexus.identity.web.dto.request.RegisterRequest;
import com.nexus.identity.web.dto.response.LoginResponse;
import com.nexus.identity.web.dto.response.RegisterResponse;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock private UserCommandService commandService;
    @Mock private JwksEndpointProvider jwksProvider;
    @Mock private Tracer tracer;
    @Mock private HttpServletRequest request;

    private AuthController controller;

    @BeforeEach
    void setUp() {
        controller = new AuthController(commandService, jwksProvider, tracer);
    }

    @Test
    void registerReturns201WithBody() {
        RegisterRequest req = new RegisterRequest("user@example.com", "Sup3rSecret!Pass",
                "Jane Doe", "+5215512345678", LocalDate.of(1990, 1, 1), "MX");
        RegisterResponse mockResponse = new RegisterResponse(UUID.randomUUID().toString(), "Registration successful.");
        when(commandService.register(eq(req), any(), any(), anyString())).thenReturn(mockResponse);

        ResponseEntity<RegisterResponse> response = controller.register(req, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(mockResponse);
    }

    @Test
    void loginSetsRefreshTokenAsHttpOnlyCookieAndStripsFromBody() {
        LoginRequest req = new LoginRequest("user@example.com", "pw", "device-1");
        LoginResponse mockResponse = new LoginResponse("access-tok", "refresh-tok", 900L, "Bearer",
                UUID.randomUUID().toString(), List.of("USER"), null, null, null);
        when(commandService.login(eq(req), any(), any(), anyString())).thenReturn(mockResponse);

        ResponseEntity<LoginResponse> response = controller.login(req, request);

        assertThat(response.getBody().refreshToken()).isNull();
        assertThat(response.getBody().accessToken()).isEqualTo("access-tok");
        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie).contains("refreshToken=refresh-tok");
        assertThat(setCookie).contains("HttpOnly");
        assertThat(setCookie).contains("Secure");
    }

    @Test
    void loginAddsSecondCookieWhenCognitoRefreshTokenPresent() {
        LoginRequest req = new LoginRequest("user@example.com", "pw", "device-1");
        LoginResponse mockResponse = new LoginResponse("access-tok", "refresh-tok", 900L, "Bearer",
                UUID.randomUUID().toString(), List.of("USER"), "cog-access", "cog-id", "cog-refresh");
        when(commandService.login(eq(req), any(), any(), anyString())).thenReturn(mockResponse);

        ResponseEntity<LoginResponse> response = controller.login(req, request);

        List<String> cookies = response.getHeaders().get("Set-Cookie");
        assertThat(cookies).hasSize(2);
        assertThat(cookies.stream().anyMatch(c -> c.contains("cognitoRefreshToken=cog-refresh"))).isTrue();
        assertThat(response.getBody().cognitoRefreshToken()).isNull();
    }

    @Test
    void logoutReturns400WhenNoAuthorizationHeader() {
        when(request.getHeader("Authorization")).thenReturn(null);

        ResponseEntity<Map<String, String>> response = controller.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(commandService);
    }

    @Test
    void logoutReturns400WhenHeaderNotBearer() {
        when(request.getHeader("Authorization")).thenReturn("Basic abc123");

        ResponseEntity<Map<String, String>> response = controller.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void logoutDecodesTokenAndDelegatesToCommandService() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        var pair = gen.generateKeyPair();
        UUID userId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();

        String token = JWT.create()
                .withSubject(userId.toString())
                .withJWTId(jti)
                .withExpiresAt(java.util.Date.from(Instant.now().plusSeconds(900)))
                .sign(Algorithm.RSA256(null, (RSAPrivateKey) pair.getPrivate()));

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        ResponseEntity<Map<String, String>> response = controller.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(commandService).logout(eq(userId), eq(jti), any(), any(), anyString());
        String setCookie = response.getHeaders().getFirst("Set-Cookie");
        assertThat(setCookie).contains("refreshToken=");
        assertThat(setCookie).contains("Max-Age=0");
    }

    @Test
    void logoutSwallowsMalformedTokenAndStillClearsCookie() {
        when(request.getHeader("Authorization")).thenReturn("Bearer not-a-real-jwt");

        ResponseEntity<Map<String, String>> response = controller.logout(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(commandService);
    }

    @Test
    void getJwksReturnsProviderResultWithCacheHeader() {
        Map<String, Object> jwks = Map.of("keys", List.of());
        when(jwksProvider.getJwks()).thenReturn(jwks);

        ResponseEntity<Map<String, Object>> response = controller.getJwks();

        assertThat(response.getBody()).isEqualTo(jwks);
        assertThat(response.getHeaders().getFirst("Cache-Control")).contains("max-age=3600");
    }

    @Test
    void requestPasswordResetAlwaysReturns200() {
        doThrow(new RuntimeException("infra failure"))
                .when(commandService).requestPasswordReset(anyString());

        ResponseEntity<Map<String, String>> response =
                controller.requestPasswordReset(new PasswordResetRequest("user@example.com"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("message")).contains("If this email is registered");
    }
}
