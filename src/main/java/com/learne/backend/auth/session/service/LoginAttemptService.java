package com.learne.backend.auth.session.service;

import com.learne.backend.ratelimit.RateLimitProperties;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class LoginAttemptService {

    private final RateLimitProperties properties;
    private final ObjectProvider<RedisLoginAttemptStore> stores;
    private final Object[] locks = new Object[64];

    public LoginAttemptService(RateLimitProperties properties, ObjectProvider<RedisLoginAttemptStore> stores) {
        this.properties = properties;
        this.stores = stores;
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
    }

    public <T> T withEmailLock(String email, Supplier<T> action) {
        synchronized (lockFor(email)) {
            return action.get();
        }
    }

    public void assertCanAttempt(String email) {
        if (!properties.enabled()) return;
        stores.getObject().assertCanAttempt(normalizeEmail(email));
    }

    public void recordFailure(String email) {
        if (!properties.enabled()) return;
        stores.getObject().recordFailure(normalizeEmail(email));
    }

    public void clear(String email) {
        if (!properties.enabled()) return;
        stores.getObject().clear(normalizeEmail(email));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private Object lockFor(String email) {
        return locks[Math.floorMod(normalizeEmail(email).hashCode(), locks.length)];
    }
}
