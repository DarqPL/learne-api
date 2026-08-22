package com.learne.backend.teacher.profile;

enum CloudinaryResourceType {
    IMAGE("image"),
    VIDEO("video");

    private final String cloudinaryValue;

    CloudinaryResourceType(String cloudinaryValue) {
        this.cloudinaryValue = cloudinaryValue;
    }

    String cloudinaryValue() {
        return cloudinaryValue;
    }
}
