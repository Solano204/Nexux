package com.nexus.identity.infrastructure.jwt;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
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
 * Keys stored in encrypted JKS (Java KeyStore) on disk volume.
 */
@Slf4j
@Component
public class JwtKeyManager {

    @Value("${nexus.jwt.keystore.path:/app/keys/nexus-identity.jks}")
    private String keystorePath;

    @Value("${nexus.jwt.keystore.password:#{environment['JWT_KEYSTORE_PASSWORD']}}")
    private String keystorePassword;

    @Value("${nexus.jwt.key.rotation-days:90}")
    private int rotationDays;

    private final ObservationRegistry observationRegistry;

    // Key ring: kid → public key (for JWKS endpoint)
    private final Map<String, RSAPublicKey> publicKeyRing =
            new LinkedHashMap<>();

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

            log.info("JWT key manager initialized. " +
                            "Current kid: {}, keys in ring: {}",
                    currentKid, publicKeyRing.size());

        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to initialize JWT key manager", e);
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

        long daysOld = java.time.Duration.between(keyCreatedAt, Instant.now())
                .toDays();

        if (daysOld >= rotationDays) {
            log.info("JWT key rotation triggered. Key age: {} days", daysOld);
            rotateKeys();
        }
    }

    private void rotateKeys() {
        Observation obs = Observation.createNotStarted(
                "jwt.key.rotation", observationRegistry).start();

        try {
            // Generate new key pair
            KeyPair newPair = generateRsaKeyPair();
            String newKid = generateKid();

            // Move current to previous (retain in ring)
            // New key becomes current
            if (publicKeyRing.size() >= 2) {
                // Remove oldest key (beyond the 30-day retention window)
                String oldestKid = publicKeyRing.keySet().iterator().next();
                publicKeyRing.remove(oldestKid);
                log.info("Removed old key from ring: {}", oldestKid);
            }

            // Add new key to ring
            publicKeyRing.put(newKid, (RSAPublicKey) newPair.getPublic());
            currentKid = newKid;
            currentPrivateKey = (RSAPrivateKey) newPair.getPrivate();
            keyCreatedAt = Instant.now();

            // Persist to KeyStore
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
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private String generateKid() {
        return "nexus-key-" + Instant.now().getEpochSecond();
    }

    private void loadFromKeyStore(File file) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(file)) {
            ks.load(fis, keystorePassword.toCharArray());
        }

        java.util.Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            java.security.cert.Certificate cert = ks.getCertificate(alias);
            if (cert != null) {
                publicKeyRing.put(alias, (RSAPublicKey) cert.getPublicKey());
                // Last alias becomes current (most recently added)
                currentKid = alias;
                java.security.KeyStore.PrivateKeyEntry entry =
                        (java.security.KeyStore.PrivateKeyEntry)
                                ks.getEntry(alias,
                                        new KeyStore.PasswordProtection(
                                                keystorePassword.toCharArray()));
                currentPrivateKey = (RSAPrivateKey) entry.getPrivateKey();
            }
        }

        keyCreatedAt = Instant.now(); // Approximate — load from metadata in prod
    }

    private void saveToKeyStore(KeyPair pair, String alias) throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        File file = new File(keystorePath);

        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                ks.load(fis, keystorePassword.toCharArray());
            }
        } else {
            ks.load(null, null);
        }

        // Self-signed certificate for KeyStore entry
        java.security.cert.Certificate selfSigned =
                generateSelfSignedCert(pair, alias);

        ks.setKeyEntry(
                alias,
                pair.getPrivate(),
                keystorePassword.toCharArray(),
                new java.security.cert.Certificate[]{selfSigned}
        );

        try (FileOutputStream fos = new FileOutputStream(file)) {
            ks.store(fos, keystorePassword.toCharArray());
        }
    }

    private java.security.cert.Certificate generateSelfSignedCert(
            KeyPair pair, String alias) throws Exception {
        // In production: use Bouncy Castle X509v3CertificateBuilder
        // For simplicity here: use sun.security internal classes
        var spec = new sun.security.x509.X500Name(
                "CN=" + alias + ", O=NexusBank, C=MX");
        var info = new sun.security.x509.X509CertInfo();
        var interval = new sun.security.x509.CertificateValidity(
                java.util.Date.from(Instant.now()),
                java.util.Date.from(Instant.now().plus(
                        java.time.Duration.ofDays(365))));
        info.set(sun.security.x509.X509CertInfo.VALIDITY, interval);
        info.set(sun.security.x509.X509CertInfo.SUBJECT,
                new sun.security.x509.CertificateSubjectName(spec));
        info.set(sun.security.x509.X509CertInfo.ISSUER,
                new sun.security.x509.CertificateIssuerName(spec));
        info.set(sun.security.x509.X509CertInfo.KEY,
                new sun.security.x509.CertificateX509Key(pair.getPublic()));
        info.set(sun.security.x509.X509CertInfo.VERSION,
                new sun.security.x509.CertificateVersion(
                        sun.security.x509.CertificateVersion.V3));
        var algo = new sun.security.x509.AlgorithmId(
                sun.security.x509.AlgorithmId.sha256WithRSAEncryption_oid);
        info.set(sun.security.x509.X509CertInfo.ALGORITHM_ID,
                new sun.security.x509.CertificateAlgorithmId(algo));
        var cert = new sun.security.x509.X509CertImpl(info);
        cert.sign(pair.getPrivate(), "SHA256WithRSA");
        return cert;
    }

    // ─── Accessors used by JwtIssuer and JwksEndpointProvider ───

    public String getCurrentKid() { return currentKid; }
    public RSAPrivateKey getCurrentPrivateKey() { return currentPrivateKey; }
    public Map<String, RSAPublicKey> getPublicKeyRing() {
        return java.util.Collections.unmodifiableMap(publicKeyRing);
    }
}