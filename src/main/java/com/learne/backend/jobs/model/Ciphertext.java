package com.learne.backend.jobs.model;

/** Encrypted envelope only. Array ownership never crosses this record's boundary. */
public record Ciphertext(String keyId, byte[] nonce, byte[] bytes) {
    public Ciphertext {
        if (keyId == null || keyId.isBlank() || keyId.length() > 64 || keyId.indexOf('\0') >= 0
                || nonce == null || nonce.length != 12 || bytes == null || bytes.length > 4096 + 16) {
            throw new IllegalArgumentException("Invalid job ciphertext envelope");
        }
        nonce = nonce.clone();
        bytes = bytes.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "Ciphertext[REDACTED]";
    }
}
