package bobcrew.server;

import java.time.Instant;

/**
 * A snapshot of a session container's resource use, as {@code docker stats} reports it. Any figure Docker
 * does not give (it depends on the cgroup version and the storage driver) is null.
 *
 * @param cpuPercent CPU use over the sampling interval; 100 is one full core
 * @param memoryUsageBytes memory in use, page cache that can be reclaimed not counted
 * @param memoryLimitBytes the container's memory limit
 * @param networkRxBytes bytes received since the container started, all interfaces
 * @param blockReadBytes bytes read from block devices since the container started
 */
record ContainerMetrics(String containerId, String image, String state, Instant startedAt, Long uptimeSeconds,
        Double cpuPercent, Long memoryUsageBytes, Long memoryLimitBytes, Long pids, Long networkRxBytes,
        Long networkTxBytes, Long blockReadBytes, Long blockWriteBytes) {
}
