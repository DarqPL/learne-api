package com.learne.backend.ratelimit;

final class CapacityMeasurements {
    static long maxGapNanos(java.util.List<Long> times) {
        long max = 0;
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i) < times.get(i - 1)) return Long.MAX_VALUE;
            max = Math.max(max, times.get(i) - times.get(i - 1));
        }
        return max;
    }
    static boolean monitoringCovered(java.util.List<Long> times, long end) {
        long bound = 2_000_000_000L;
        return !times.isEmpty() && end >= 0 && times.getFirst() >= 0 && times.getFirst() <= bound
                && times.getLast() <= end && end - times.getLast() <= bound && maxGapNanos(times) <= bound;
    }
    static long percentile(long[] values, double quantile) {
        if (values.length == 0 || quantile <= 0 || quantile > 1) throw new IllegalArgumentException();
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[(int) Math.ceil(quantile * sorted.length) - 1];
    }
    static boolean allowed(int index) { return index % 4 < 2; }
    static String user(int index) { return Integer.toString(1 + (index / 4 * 2 + index % 2) % 100); }
    static long offsetNanos(int index) { return index * 10_000_000L; }
}
