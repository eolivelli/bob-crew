package bobcrew.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class ResourcesTest {

    private static BobCrewProperties.Docker docker(String memoryLimit, double cpus, String maxMemory, double maxCpus) {
        return new BobCrewProperties.Docker("unix:///var/run/docker.sock", "1.44", "t", "img", "1000:1000",
                memoryLimit == null ? null : DataSize.parse(memoryLimit), cpus, 0,
                maxMemory == null ? null : DataSize.parse(maxMemory), maxCpus, "bridge", Duration.ofSeconds(5), 32, 32768,
                java.util.List.of("bobcrew/*"));
    }

    @Test
    void noRequestAndNoSettingsMeansNoLimits() {
        assertThat(Resources.of(null, null, docker(null, 0, null, 0))).isEqualTo(new Resources(null, null));
        assertThat(Resources.of(" ", null, docker("0", 0, "0", 0))).isEqualTo(new Resources(null, null));
    }

    @Test
    void withoutARequestTheDefaultAppliesThenTheCap() {
        assertThat(Resources.of(null, null, docker("2GB", 1, "8GB", 4)))
            .isEqualTo(new Resources(2L << 30, 1.0));
        assertThat(Resources.of(null, null, docker(null, 0, "8GB", 4)))
            .isEqualTo(new Resources(8L << 30, 4.0));
    }

    @Test
    void aRequestWinsWithinTheCap() {
        assertThat(Resources.of("16gb", 8.0, docker("2GB", 1, null, 0)))
            .isEqualTo(new Resources(16L << 30, 8.0));
        assertThatThrownBy(() -> Resources.of("16GB", null, docker(null, 0, "8GB", 0)))
            .hasMessageContaining("at most 8192MB");
        assertThatThrownBy(() -> Resources.of(null, 1.5, docker(null, 0, null, 1)))
            .hasMessageContaining("at most 1.0 CPUs");
        assertThatThrownBy(() -> Resources.of("10", null, docker(null, 0, null, 0))).hasMessageContaining("64MB");
        assertThatThrownBy(() -> Resources.of(null, Double.NaN, docker(null, 0, null, 0)))
            .hasMessageContaining("CPUs");
    }
}
