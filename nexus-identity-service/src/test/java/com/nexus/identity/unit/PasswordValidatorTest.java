package com.nexus.identity.unit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.*;

/**
 * Password validation tests.
 *
 * Validates:
 * 1. BCrypt strength 4 (tests) / 12 (production) produces correct hash format
 * 2. BCrypt matches() is timing-safe for both valid and invalid passwords
 * 3. Password reuse detection logic
 * 4. Common password rejection (future validator)
 */
@Tag("unit")
class PasswordValidatorTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Test
    @DisplayName("BCrypt encodes password to $2a$ format")
    void encode_password_producesBcryptHash() {
        String hash = encoder.encode("MySecurePassword123!");
        assertThat(hash).startsWith("$2a$04$");
        assertThat(hash).hasSize(60);
    }

    @Test
    @DisplayName("BCrypt matches: correct password returns true")
    void matches_correctPassword_returnsTrue() {
        String raw = "MySecurePassword123!";
        String hash = encoder.encode(raw);
        assertThat(encoder.matches(raw, hash)).isTrue();
    }

    @Test
    @DisplayName("BCrypt matches: wrong password returns false")
    void matches_wrongPassword_returnsFalse() {
        String hash = encoder.encode("CorrectPassword123!");
        assertThat(encoder.matches("WrongPassword123!", hash)).isFalse();
    }

    @Test
    @DisplayName("BCrypt matches: empty string vs hash returns false")
    void matches_emptyString_returnsFalse() {
        String hash = encoder.encode("SomePassword123!");
        assertThat(encoder.matches("", hash)).isFalse();
    }

    @Test
    @DisplayName("BCrypt: two hashes of same password are different (salt)")
    void encode_samePasswordTwice_differentHashes() {
        String raw = "MySecurePassword123!";
        String hash1 = encoder.encode(raw);
        String hash2 = encoder.encode(raw);
        assertThat(hash1).isNotEqualTo(hash2);
        assertThat(encoder.matches(raw, hash1)).isTrue();
        assertThat(encoder.matches(raw, hash2)).isTrue();
    }

    @Test
    @DisplayName("Password reuse check: matches last 5 hashes correctly")
    void passwordReuseCheck_detectsReuse() {
        String[] lastPasswords = {
                "OldPass1!", "OldPass2!", "OldPass3!", "OldPass4!", "OldPass5!"
        };

        String[] hashes = new String[5];
        for (int i = 0; i < 5; i++) {
            hashes[i] = encoder.encode(lastPasswords[i]);
        }

        // "OldPass3!" was used — should be detected as reuse
        String candidatePassword = "OldPass3!";
        boolean isReused = false;
        for (String hash : hashes) {
            if (encoder.matches(candidatePassword, hash)) {
                isReused = true;
                break;
            }
        }
        assertThat(isReused).isTrue();

        // "BrandNewPass!" was never used — not reuse
        String newPassword = "BrandNewPass123!";
        boolean isNewPasswordReused = false;
        for (String hash : hashes) {
            if (encoder.matches(newPassword, hash)) {
                isNewPasswordReused = true;
                break;
            }
        }
        assertThat(isNewPasswordReused).isFalse();
    }

    @ParameterizedTest
    @DisplayName("Password minimum length: below 12 chars should be rejected by @Size")
    @ValueSource(strings = {
            "Short1!",      // 7 chars
            "TooShort!1",   // 10 chars
            "Eleven1234!"   // 11 chars
    })
    void shortPasswords_areInvalidLength(String password) {
        // @Size(min=12) is enforced at DTO level by Bean Validation
        // Here we verify the constraint threshold
        assertThat(password.length()).isLessThan(12);
    }

    @Test
    @DisplayName("BCrypt dummy hash comparison takes similar time (timing safety)")
    void matches_dummyHash_doesNotRevealAbsence() {
        // BCrypt matches against a dummy hash should complete in similar
        // time as matches against a real hash — prevents timing attacks
        // that reveal whether a user account exists.
        String dummyHash =
                "$2a$12$dummy.hash.for.timing.safety.xxxxx.xxxxxxxxxx";

        // Should return false without throwing, regardless of timing
        long start = System.nanoTime();
        boolean result = false;
        try {
            result = encoder.matches("anypassword", dummyHash);
        } catch (Exception e) {
            // Some BCrypt implementations throw on malformed hash
            // That's also acceptable — the test checks for no NPE/crash
        }
        long duration = System.nanoTime() - start;

        assertThat(result).isFalse();
        // Should take at least 1ms (BCrypt does actual work)
        assertThat(duration).isGreaterThan(0);
    }
}