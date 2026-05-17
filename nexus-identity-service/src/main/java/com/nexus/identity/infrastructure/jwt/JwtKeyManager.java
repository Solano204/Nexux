package com.nexus.identity.infrastructure.jwt;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JWT Key Manager — RSA key pair lifecycle management.
 *
 * Key ring pattern:
 * - Holds CURRENT key (used for signing new tokens)
 * - Holds PREVIOUS key (retained for 30 days after rotation)
 * - Both published via JWKS endpoint
 *
 * Key rotation every 90 days (scheduled).
 * Keys stored in encrypted PKCS12 KeyStore on disk volume.
 *
 * Self-signed cert generation uses Bouncy Castle (bcpkix-jvm)
 * instead of the removed sun.security.x509 internal APIs.
 *
 * Required dependency (add to pom.xml / build.gradle):
 *   <dependency>
 *     <groupId>org.bouncycastle</groupId>
 *     <artifactId>bcpkix-jdk18on</artifactId>
 *     <version>1.78.1</version>
 *   </dependency>
 */
@Slf4j
@Component
public class JwtKeyManager {

    @Value("${nexus.jwt.keystore.path:/app/keys/nexus-identity.p12}")
    private String keystorePath;

    @Value("${nexus.jwt.keystore.password:#{environment['JWT_KEYSTORE_PASSWORD']}}")
    private String keystorePassword;

    @Value("${nexus.jwt.key.rotation-days:90}")
    private int rotationDays;

    private final ObservationRegistry observationRegistry;

    // Shared SecureRandom — thread-safe, reused across calls.
    // new SecureRandom() is preferred over getInstanceStrong() for token
    // generation: non-blocking, still cryptographically strong, and safe
    // in low-entropy container environments (Docker/Kubernetes).
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // Key ring: kid → public key (for JWKS endpoint)
    private final Map<String, RSAPublicKey> publicKeyRing = new LinkedHashMap<>();

    // Current signing key pair
    private String currentKid;
    private RSAPrivateKey currentPrivateKey;
    private Instant keyCreatedAt;

    public JwtKeyManager(ObservationRegistry observationRegistry) {
        this.observationRegistry = observationRegistry;
    }

    @PostConstruct
    public void initialize() {
        try {
            File keystoreFile = new File(keystorePath);

            if (keystoreFile.exists()) {
                log.info("Loading existing key store from: {}", keystorePath);
                loadFromKeyStore(keystoreFile);
            } else {
                log.info("No key store found, generating new RSA key pair");
                keystoreFile.getParentFile().mkdirs();
                generateAndStoreNewKeyPair();
            }

            log.info("JWT key manager initialized. Current kid: {}, keys in ring: {}",
                    currentKid, publicKeyRing.size());

        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize JWT key manager", e);
        }
    }

    /**
     * Scheduled key rotation every 90 days.
     * New key becomes current. Old key retained for 30 days
     * to allow existing tokens to expire gracefully.
     */
    @Scheduled(fixedRateString = "${nexus.jwt.key.rotation-check-hours:24}",
            timeUnit = TimeUnit.HOURS)
    public void checkKeyRotation() {
        if (keyCreatedAt == null) return;

        long daysOld = java.time.Duration.between(keyCreatedAt, Instant.now()).toDays();

        if (daysOld >= rotationDays) {
            log.info("JWT key rotation triggered. Key age: {} days", daysOld);
            rotateKeys();
        }
    }

    private void rotateKeys() {
        Observation obs = Observation.createNotStarted(
                "jwt.key.rotation", observationRegistry).start();

        try {
            KeyPair newPair = generateRsaKeyPair();
            String newKid = generateKid();

            if (publicKeyRing.size() >= 2) {
                // Remove oldest key (beyond the 30-day retention window)
                String oldestKid = publicKeyRing.keySet().iterator().next();
                publicKeyRing.remove(oldestKid);
                log.info("Removed old key from ring: {}", oldestKid);
            }

            publicKeyRing.put(newKid, (RSAPublicKey) newPair.getPublic());
            currentKid = newKid;
            currentPrivateKey = (RSAPrivateKey) newPair.getPrivate();
            keyCreatedAt = Instant.now();

            saveToKeyStore(newPair, newKid);

            obs.event(Observation.Event.of("key.rotation.success"));
            log.info("JWT key rotation complete. New kid: {}", newKid);

        } catch (Exception e) {
            obs.error(e);
            log.error("JWT key rotation failed: {}", e.getMessage(), e);
        } finally {
            obs.stop();
        }
    }

    private void generateAndStoreNewKeyPair() throws Exception {
        KeyPair pair = generateRsaKeyPair();
        currentKid = generateKid();
        currentPrivateKey = (RSAPrivateKey) pair.getPrivate();
        publicKeyRing.put(currentKid, (RSAPublicKey) pair.getPublic());
        keyCreatedAt = Instant.now();
        saveToKeyStore(pair, currentKid);
    }

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048, SECURE_RANDOM);
        return gen.generateKeyPair();
    }

    private String generateKid() {
        return "nexus-key-" + Instant.now().getEpochSecond();
    }

    private void loadFromKeyStore(File file) throws Exception {
        // ── CHANGED: PKCS12 instead of JKS.
        // JKS is a proprietary Sun format, deprecated since Java 9.
        // PKCS12 is the standard, works identically with KeyStore API.
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(file)) {
            ks.load(fis, keystorePassword.toCharArray());
        }

        java.util.Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate cert = ks.getCertificate(alias);
            if (cert != null) {
                publicKeyRing.put(alias, (RSAPublicKey) cert.getPublicKey());
                currentKid = alias; // last alias = most recently added
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)
                        ks.getEntry(alias,
                                new KeyStore.PasswordProtection(
                                        keystorePassword.toCharArray()));
                currentPrivateKey = (RSAPrivateKey) entry.getPrivateKey();
            }
        }

        keyCreatedAt = Instant.now(); // approximate — load from metadata in prod
    }

    private void saveToKeyStore(KeyPair pair, String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        File file = new File(keystorePath);

        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                ks.load(fis, keystorePassword.toCharArray());
            }
        } else {
            ks.load(null, null);
        }

        Certificate selfSigned = generateSelfSignedCert(pair, alias);

        ks.setKeyEntry(
                alias,
                pair.getPrivate(),
                keystorePassword.toCharArray(),
                new Certificate[]{selfSigned}
        );

        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, keystorePassword.toCharArray());
        }
    }

    /**
     * Generates a self-signed X.509 v3 certificate using Bouncy Castle.
     *
     * Replaces the removed sun.security.x509.* internal API classes:
     *   X500Name, X509CertInfo, CertificateValidity, X509CertImpl, etc.
     *   were encapsulated in Java 17 and are fully inaccessible in Java 21+.
     *
     * Bouncy Castle equivalent:
     *   X500Name          → org.bouncycastle.asn1.x500.X500Name
     *   X509v3CertBuilder → org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
     *   cert.sign()       → JcaContentSignerBuilder + JcaX509CertificateConverter
     */
    private Certificate generateSelfSignedCert(KeyPair pair, String alias)
            throws Exception {

        // Distinguished name — same fields as the original sun.security version
        X500Name dn = new X500Name("CN=" + alias + ", O=NexusBank, C=MX");

        // Serial number — random 128-bit value (collision-proof, RFC 5280 compliant)
        BigInteger serial = new BigInteger(128, SECURE_RANDOM);

        Date notBefore = Date.from(Instant.now());
        Date notAfter  = Date.from(Instant.now().plus(java.time.Duration.ofDays(365)));

        // JcaX509v3CertificateBuilder: issuer == subject for self-signed cert
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                dn,          // issuer
                serial,
                notBefore,
                notAfter,
                dn,          // subject (same as issuer = self-signed)
                pair.getPublic()
        );

        // ContentSigner replaces cert.sign(privateKey, "SHA256WithRSA")
        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setSecureRandom(SECURE_RANDOM)
                .build(pair.getPrivate());

        // Convert to standard java.security.cert.X509Certificate
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        cert.verify(pair.getPublic()); // sanity check — ensures cert is self-consistent
        return cert;
    }

    // ─── Accessors used by JwtIssuer and JwksEndpointProvider ───

    public String getCurrentKid() { return currentKid; }
    public RSAPrivateKey getCurrentPrivateKey() { return currentPrivateKey; }
    public Map<String, RSAPublicKey> getPublicKeyRing() {
        return Collections.unmodifiableMap(publicKeyRing);
    }
}