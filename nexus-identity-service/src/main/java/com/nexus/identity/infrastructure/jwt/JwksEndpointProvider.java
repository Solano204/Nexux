package com.nexus.identity.infrastructure.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Map;

/**
 * JWKS Endpoint Provider — Builds the JWKS JSON response.
 *
 * Returns all public keys in the key ring.
 * During key rotation: returns BOTH current and previous key.
 * The API Gateway caches this response and uses `kid` to select
 * the correct key for each token.
 */
@Component
@RequiredArgsConstructor
public class JwksEndpointProvider {

    private final JwtKeyManager keyManager;
    private final ObjectMapper objectMapper;

    /**
     * Returns JWKS JSON:
     * {"keys": [{"kty":"RSA","use":"sig","kid":"...","n":"...","e":"..."}]}
     */
    public Map<String, Object> getJwks() {
        ArrayNode keysArray = objectMapper.createArrayNode();

        for (Map.Entry<String, RSAPublicKey> entry :
                keyManager.getPublicKeyRing().entrySet()) {

            RSAPublicKey key = entry.getValue();
            ObjectNode keyNode = objectMapper.createObjectNode();

            keyNode.put("kty", "RSA");
            keyNode.put("use", "sig");
            keyNode.put("alg", "RS256");
            keyNode.put("kid", entry.getKey());

            // RSA modulus (n) and public exponent (e) in Base64URL encoding
            keyNode.put("n", Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(key.getModulus().toByteArray()));
            keyNode.put("e", Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(key.getPublicExponent().toByteArray()));

            keysArray.add(keyNode);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("keys", keysArray);

        return objectMapper.convertValue(result, Map.class);
    }
}