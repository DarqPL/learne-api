package com.learne.backend.ratelimit;

import static org.assertj.core.api.Assertions.*;

import com.learne.backend.cache.CapacityCacheFixture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CapacityMeasurementsTests {
    @Test
    void nearestRankIncludesTailAndDoesNotInterpolate() {
        assertThat(CapacityMeasurements.percentile(new long[] {100, 1, 3, 2}, .95)).isEqualTo(100);
        assertThat(CapacityMeasurements.percentile(new long[] {100, 1, 3, 2}, .50)).isEqualTo(2);
        assertThatThrownBy(() -> CapacityMeasurements.percentile(new long[0], .95))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixedScheduleBalancesWorkersAndCohortsAndReusesAllowedUsersEveryTwoSeconds() {
        for (int worker = 0; worker < 2; worker++) {
            int w = worker;
            assertThat(IntStream.range(0, 6000).filter(i -> i % 2 == w)
                    .filter(CapacityMeasurements::allowed).count()).isEqualTo(1500);
        }
        assertThat(CapacityMeasurements.offsetNanos(5999)).isEqualTo(59_990_000_000L);
        for (int i = 0; i < 200; i++) {
            if (CapacityMeasurements.allowed(i)) {
                assertThat(CapacityMeasurements.user(i + 200)).isEqualTo(CapacityMeasurements.user(i));
                assertThat(CapacityMeasurements.offsetNanos(i + 200) - CapacityMeasurements.offsetNanos(i))
                        .isEqualTo(2_000_000_000L);
            }
        }
        assertThat(IntStream.range(0, 200).filter(CapacityMeasurements::allowed)
                .mapToObj(CapacityMeasurements::user).distinct().count()).isEqualTo(100);
    }

    @Test
    void cacheFixtureUsesActualSerializerForExactBytesAndAsciiKeys() {
        assertThat(CapacityCacheFixture.value()).hasSize(16384);
        assertThat(CapacityCacheFixture.roundTrip()).isTrue();
        assertThat(IntStream.range(0, 1024).mapToObj(CapacityCacheFixture::key).distinct().count()).isEqualTo(1024);
        assertThat(CapacityCacheFixture.key(1023)).hasSize(128).matches("[\\x20-\\x7e]+");
    }
}
