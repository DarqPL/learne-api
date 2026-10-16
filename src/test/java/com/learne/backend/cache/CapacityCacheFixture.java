package com.learne.backend.cache;

/** Test-only bridge; production serializer visibility stays package-private. */
public final class CapacityCacheFixture {
    public static byte[] value() {
        int padding = 16384;
        for (int attempt = 0; attempt < 10; attempt++) {
            var buffer = CacheConfig.redisValueSerializer().write("x".repeat(padding));
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            if (bytes.length == 16384) return bytes;
            padding += 16384 - bytes.length;
        }
        throw new AssertionError("Cannot produce exact serialized fixture length");
    }
    public static String key(int index) {
        String suffix = Integer.toString(index);
        String prefix = "task9-cache-";
        return prefix + "c".repeat(128 - prefix.length() - 1 - suffix.length()) + ":" + suffix;
    }
    public static boolean roundTrip() {
        Object decoded = CacheConfig.redisValueSerializer().read(java.nio.ByteBuffer.wrap(value()));
        return decoded instanceof String text && text.chars().allMatch(c -> c == 'x');
    }
}
