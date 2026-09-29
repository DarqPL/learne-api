package com.learne.backend.cache;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

@Service
public class CatalogCacheVersionService {
    private final AtomicLong version = new AtomicLong(1L);

    public long currentVersion() {
        return version.get();
    }

    public long bumpVersion() {
        return version.incrementAndGet();
    }
}
