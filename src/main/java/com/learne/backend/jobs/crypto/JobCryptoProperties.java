package com.learne.backend.jobs.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binding only; the cipher validates and freezes its own keyring. */
@ConfigurationProperties("app.job-crypto")
public class JobCryptoProperties {
    private String activeKeyId = "";
    private String keysJson = "";
    public String getActiveKeyId() { return activeKeyId; }
    public void setActiveKeyId(String value) { activeKeyId = value; }
    public String getKeysJson() { return keysJson; }
    public void setKeysJson(String value) { keysJson = value; }
    @Override public String toString() { return "JobCryptoProperties[REDACTED]"; }
}
