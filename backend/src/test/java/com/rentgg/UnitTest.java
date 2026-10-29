package com.rentgg;

import com.rentgg.order.domain.Money;
import com.rentgg.shared.crypto.CredentialCipher;
import com.rentgg.shared.crypto.Hmac;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnitTest {

    private static final String KEYS = "a:MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=,b:YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=";

    @Test
    void moneyFormulas() {
        assertThat(Money.rent(30_000, 180)).isEqualTo(90_000);
        assertThat(Money.rent(25_000, 125)).isEqualTo(52_083); // floor
        assertThat(Money.fee(90_000, 1000)).isEqualTo(9_000);
        assertThat(Money.fee(52_083, 1000)).isEqualTo(5_208);
        assertThat(Money.bps(90_000, 5000)).isEqualTo(45_000);
        assertThat(Money.proportionalFee(45_000, 9_000, 90_000)).isEqualTo(4_500);
    }

    @Test
    void cipherRoundTripAndTamperDetection() {
        CredentialCipher c = new CredentialCipher(KEYS, "a");
        UUID acc = UUID.randomUUID();
        byte[] plain = "{\"username\":\"u\",\"password\":\"p\"}".getBytes(StandardCharsets.UTF_8);
        var e1 = c.encrypt(acc, 1, plain);
        var e2 = c.encrypt(acc, 1, plain);
        assertThat(e1.iv()).hasSize(12).isNotEqualTo(e2.iv());
        assertThat(c.decrypt(acc, 1, e1.ciphertext(), e1.iv(), "a")).isEqualTo(plain);
        // Wrong AAD (other account or version) must fail.
        assertThatThrownBy(() -> c.decrypt(UUID.randomUUID(), 1, e1.ciphertext(), e1.iv(), "a")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt(acc, 2, e1.ciphertext(), e1.iv(), "a")).isInstanceOf(IllegalStateException.class);
        byte[] tampered = e1.ciphertext().clone();
        tampered[0] ^= 1;
        assertThatThrownBy(() -> c.decrypt(acc, 1, tampered, e1.iv(), "a")).isInstanceOf(IllegalStateException.class);
        // Key rotation: records encrypted with an old key stay readable by key id.
        CredentialCipher rotated = new CredentialCipher(KEYS, "b");
        assertThat(rotated.decrypt(acc, 1, e1.ciphertext(), e1.iv(), "a")).isEqualTo(plain);
        assertThat(rotated.encrypt(acc, 1, plain).keyId()).isEqualTo("b");
    }

    @Test
    void hmac() {
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = Hmac.sha256Hex("secret", body);
        assertThat(Hmac.verify("secret", body, sig)).isTrue();
        assertThat(Hmac.verify("secret", body, sig.toUpperCase())).isTrue();
        assertThat(Hmac.verify("other", body, sig)).isFalse();
        assertThat(Hmac.verify("secret", body, null)).isFalse();
    }
}
