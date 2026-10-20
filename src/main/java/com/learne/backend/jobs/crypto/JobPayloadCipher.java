package com.learne.backend.jobs.crypto;

import com.learne.backend.jobs.model.Ciphertext;
import com.learne.backend.jobs.model.JobType;
import com.learne.backend.jobs.service.JobErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Service
public final class JobPayloadCipher {
    private final Map<String, SecretKey> keys;
    private final String active;
    private final SecureRandom random = new SecureRandom();

    public JobPayloadCipher(JobCryptoProperties properties, ObjectMapper mapper) {
        Map<String, SecretKey> parsed = new HashMap<>();
        try {
            active = Objects.requireNonNull(properties.getActiveKeyId());
            String json = Objects.requireNonNull(properties.getKeysJson());
            if (!active.isEmpty() && !validId(active)) throw new IllegalArgumentException();
            if (!json.isBlank()) {
                var tree = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
                if (tree == null || !tree.isObject()) throw new IllegalArgumentException();
                for (String id : tree.propertyNames()) {
                    var value = tree.get(id);
                    if (!validId(id) || !value.isString()) throw new IllegalArgumentException();
                    byte[] key = Base64.getDecoder().decode(value.asString());
                    try {
                        if (key.length != 32) throw new IllegalArgumentException();
                        parsed.put(id, new SecretKeySpec(key, "AES"));
                    } finally { Arrays.fill(key, (byte) 0); }
                }
            }
            if (!active.isEmpty() && !parsed.containsKey(active)) throw new IllegalArgumentException();
            keys = Map.copyOf(parsed);
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("Invalid job crypto configuration");
        }
    }
    private static boolean validId(String id) { return id.matches("[A-Za-z0-9_-]{1,64}"); }
    public void requireActiveKey() {
        if (!keys.containsKey(active)) throw new IllegalStateException("JOB_CRYPTO_ACTIVE_KEY_REQUIRED");
    }
    public Ciphertext encrypt(UUID jobId, JobType type, String generation, byte[] plaintext) {
        requireActiveKey();
        if (plaintext == null || plaintext.length > 4096) throw new IllegalArgumentException("Invalid job plaintext");
        byte[] input = plaintext.clone(); byte[] encrypted = null;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(active), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(jobId, type, generation));
            encrypted = cipher.doFinal(input);
            return new Ciphertext(active, nonce, encrypted);
        } catch (GeneralSecurityException ignored) {
            throw new IllegalStateException("JOB_CRYPTO_ENCRYPTION_FAILED");
        } finally {
            Arrays.fill(input, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
        }
    }
    public byte[] decrypt(UUID jobId, JobType type, String generation, Ciphertext envelope) {
        byte[] bytes = null;
        try {
            SecretKey key = keys.get(envelope.keyId());
            if (key == null) throw new IllegalArgumentException();
            bytes = envelope.bytes();
            if (bytes.length < 16 || bytes.length > 4112) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, envelope.nonce()));
            cipher.updateAAD(aad(jobId, type, generation));
            return cipher.doFinal(bytes);
        } catch (GeneralSecurityException | RuntimeException ignored) {
            throw new SecurityException(JobErrorCode.PAYLOAD_DECRYPTION_FAILED.name());
        } finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    }
    private static byte[] aad(UUID id, JobType type, String generation) {
        if (id == null || type == null || generation == null || generation.length() > 4096)
            throw new IllegalArgumentException("Invalid job identity");
        byte[] a = id.toString().getBytes(StandardCharsets.UTF_8);
        byte[] b = type.name().getBytes(StandardCharsets.UTF_8);
        byte[] c = generation.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(12 + a.length + b.length + c.length)
                .putInt(a.length).put(a).putInt(b.length).put(b).putInt(c.length).put(c).array();
    }
    @Override public String toString() { return "JobPayloadCipher[REDACTED]"; }
}
