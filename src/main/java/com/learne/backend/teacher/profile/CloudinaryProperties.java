package com.learne.backend.teacher.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cloudinary")
class CloudinaryProperties {
    private String cloudName = "";
    private String apiKey = "";
    private String apiSecret = "";
    private String profileFolder = "learne/teacher-profiles";
    private long maxAvatarBytes = 5 * 1024 * 1024;
    private long maxVideoBytes = 100 * 1024 * 1024;

    public String getCloudName() { return cloudName; }
    public void setCloudName(String cloudName) { this.cloudName = cloudName; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getApiSecret() { return apiSecret; }
    public void setApiSecret(String apiSecret) { this.apiSecret = apiSecret; }
    public String getProfileFolder() { return profileFolder; }
    public void setProfileFolder(String profileFolder) { this.profileFolder = profileFolder; }
    public long getMaxAvatarBytes() { return maxAvatarBytes; }
    public void setMaxAvatarBytes(long maxAvatarBytes) { this.maxAvatarBytes = maxAvatarBytes; }
    public long getMaxVideoBytes() { return maxVideoBytes; }
    public void setMaxVideoBytes(long maxVideoBytes) { this.maxVideoBytes = maxVideoBytes; }
}
