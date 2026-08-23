package com.learne.backend.storage.dto;

public enum StorageResourceType {
    IMAGE("image"),
    VIDEO("video");

    private final String cloudinaryValue;

    StorageResourceType(String cloudinaryValue) {
        this.cloudinaryValue = cloudinaryValue;
    }

    public String cloudinaryValue() {
        return cloudinaryValue;
    }
}
