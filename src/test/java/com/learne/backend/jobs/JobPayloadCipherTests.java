package com.learne.backend.jobs;

import static org.assertj.core.api.Assertions.*;
import com.learne.backend.jobs.crypto.*;
import com.learne.backend.jobs.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class JobPayloadCipherTests {
    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final UUID ID = UUID.randomUUID();
    private static final JobType TYPE = JobType.REGISTRATION_OTP_MAIL;
    private JobPayloadCipher cipher(String active, String json) {
        var p = new JobCryptoProperties(); p.setActiveKeyId(active); p.setKeysJson(json);
        return new JobPayloadCipher(p, JsonMapper.builder().build());
    }
    private JobPayloadCipher cipher() { return cipher("v1", "{\"v1\":\"" + KEY + "\"}"); }
    @Test void roundTripFreshNoncesAndDefensiveOwnership() {
        var c = cipher(); byte[] input = "private-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var a = c.encrypt(ID, TYPE, "g", input); var b = c.encrypt(ID, TYPE, "g", input);
        assertThat(a.nonce()).hasSize(12).isNotEqualTo(b.nonce());
        assertThat(c.decrypt(ID, TYPE, "g", a)).containsExactly(input);
        assertThat(input).containsExactly("private-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(a.toString()).doesNotContain("private-marker", KEY);
    }
    private void rejected(Runnable operation) {
        assertThatThrownBy(operation::run).isExactlyInstanceOf(SecurityException.class)
                .hasMessage("PAYLOAD_DECRYPTION_FAILED").hasNoCause();
    }
    @Test void tamperingAndEveryIdentityComponentAreAuthenticated() {
        var c = cipher(); var a = c.encrypt(ID, TYPE, "g", new byte[]{1});
        rejected(() -> c.decrypt(UUID.randomUUID(), TYPE, "g", a));
        rejected(() -> c.decrypt(ID, JobType.PASSWORD_RESET_MAIL, "g", a));
        rejected(() -> c.decrypt(ID, TYPE, "other", a));
        byte[] bytes = a.bytes(); bytes[0] ^= 1;
        rejected(() -> c.decrypt(ID, TYPE, "g", new Ciphertext(a.keyId(), a.nonce(), bytes)));
        byte[] nonce = a.nonce(); nonce[0] ^= 1;
        rejected(() -> c.decrypt(ID, TYPE, "g", new Ciphertext(a.keyId(), nonce, a.bytes())));
        rejected(() -> c.decrypt(ID, TYPE, "g", new Ciphertext("missing", a.nonce(), a.bytes())));
        rejected(() -> c.decrypt(ID, TYPE, "g", null));
    }
    @Test void rotationReadsOldKeyButWritesOnlyActiveKeyAndSnapshotsConfiguration() {
        var old = cipher().encrypt(ID, TYPE, "g", new byte[]{9});
        var p = new JobCryptoProperties(); p.setActiveKeyId("v2");
        p.setKeysJson("{\"v1\":\"" + KEY + "\",\"v2\":\"" + Base64.getEncoder().encodeToString(new byte[32]) + "\"}");
        var rotated = new JobPayloadCipher(p, JsonMapper.builder().build());
        p.setKeysJson(""); p.setActiveKeyId("");
        assertThat(rotated.decrypt(ID, TYPE, "g", old)).containsExactly(9);
        assertThat(rotated.encrypt(ID, TYPE, "g", new byte[0]).keyId()).isEqualTo("v2");
        var removed = cipher("v2", "{\"v2\":\"" + KEY + "\"}");
        rejected(() -> removed.decrypt(ID, TYPE, "g", old));
    }
    @Test void missingActiveKeyIsExplicitAndLimitsAreEnforced() {
        var empty = cipher("", "");
        assertThatThrownBy(empty::requireActiveKey).hasMessage("JOB_CRYPTO_ACTIVE_KEY_REQUIRED").hasNoCause();
        assertThatThrownBy(() -> empty.encrypt(ID, TYPE, "g", new byte[0])).hasMessage("JOB_CRYPTO_ACTIVE_KEY_REQUIRED");
        var c = cipher();
        assertThat(c.decrypt(ID, TYPE, "g", c.encrypt(ID, TYPE, "g", new byte[4096]))).hasSize(4096);
        assertThatThrownBy(() -> c.encrypt(ID, TYPE, "g", new byte[4097])).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidConfigurationNeverRetainsRejectedMaterial() {
        for (String json : List.of("private-marker", "[]", "null", "{\"v1\":\"private-marker\"}",
                "{\"v1\":\"AA==\"}", "{\"v1\":null}", "{\"v1\":42}", "{\"bad key\":\"" + KEY + "\"}",
                "{\"" + "x".repeat(65) + "\":\"" + KEY + "\"}",
                "{\"v1\":\"" + Base64.getEncoder().encodeToString(new byte[33]) + "\"}",
                "{\"v1\":\"" + KEY + "\",\"v1\":\"" + KEY + "\"}", "{} {}")) {
            assertThatThrownBy(() -> cipher("v1", json)).hasMessage("Invalid job crypto configuration")
                    .hasNoCause().hasToString("java.lang.IllegalArgumentException: Invalid job crypto configuration");
        }
        assertThatThrownBy(() -> cipher("missing", "{\"v1\":\"" + KEY + "\"}"))
                .hasMessage("Invalid job crypto configuration").hasNoCause();
        var p = new JobCryptoProperties(); p.setKeysJson("private-marker");
        assertThat(p.toString()).doesNotContain("private-marker");
        assertThat(cipher().toString()).doesNotContain(KEY);
    }
}
