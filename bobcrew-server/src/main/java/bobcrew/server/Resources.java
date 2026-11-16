package bobcrew.server;

import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

/**
 * The memory and CPUs a session's container may use; null means no limit. A session can ask for its own,
 * within the server's caps ({@code bobcrew.docker.max-memory}, {@code max-cpus}); otherwise it gets the server's
 * defaults ({@code memory-limit}, {@code cpus}), or the caps, or no limit at all.
 *
 * @param memoryBytes the memory limit in bytes, or null
 * @param cpus how many CPUs' worth of time (1.5 = one and a half), or null
 */
record Resources(Long memoryBytes, Double cpus) {

    /** Docker refuses tiny limits, and an agent would not start anyway. */
    static final long MIN_MEMORY = DataSize.ofMegabytes(64).toBytes();

    static final double MAX_CPUS = 1024;

    /**
     * @param memory as requested, such as {@code 8GB} or {@code 512MB}; null or blank for the default
     * @param cpus as requested; null for the default
     * @throws ApiException 400 for a value that is not valid or exceeds the server's caps
     */
    static Resources of(String memory, Double cpus, BobCrewProperties.Docker settings) {
        Long maxMemory = positive(settings.maxMemory());
        Double maxCpus = settings.maxCpus() > 0 ? settings.maxCpus() : null;

        Long memoryBytes;
        if (memory == null || memory.isBlank()) {
            Long byDefault = positive(settings.memoryLimit());
            memoryBytes = byDefault != null ? byDefault : maxMemory;
        } else {
            try {
                memoryBytes = DataSize.parse(memory.strip().toUpperCase(java.util.Locale.ROOT)).toBytes();
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid memory size '" + memory
                        + "'; write it as, say, 8GB or 512MB");
            }
            if (memoryBytes < MIN_MEMORY) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The memory limit must be at least 64MB");
            }
            if (maxMemory != null && memoryBytes > maxMemory) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "This server allows at most "
                        + maxMemory / (1024 * 1024) + "MB of memory per session");
            }
        }

        Double checkedCpus;
        if (cpus == null) {
            checkedCpus = settings.cpus() > 0 ? Double.valueOf(settings.cpus()) : maxCpus;
        } else {
            if (cpus.isNaN() || cpus <= 0 || cpus > MAX_CPUS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The number of CPUs must be more than 0");
            }
            if (maxCpus != null && cpus > maxCpus) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "This server allows at most " + maxCpus
                        + " CPUs per session");
            }
            checkedCpus = cpus;
        }
        return new Resources(memoryBytes, checkedCpus);
    }

    private static Long positive(DataSize size) {
        return size == null || size.toBytes() <= 0 ? null : size.toBytes();
    }
}
