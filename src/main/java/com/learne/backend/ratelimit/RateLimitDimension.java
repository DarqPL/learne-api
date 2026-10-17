package com.learne.backend.ratelimit;

public enum RateLimitDimension {
    IP("ip"), EMAIL("email"), USER("user");

    private final String wire;

    RateLimitDimension(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }
}
