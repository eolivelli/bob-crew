package bobcrew.server;

import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

/**
 * The memory and CPUs a session's container may use; null means no limit. A session can ask for its own,
 * within its user's caps (the server's {@code bobcrew.docker.max-memory} and {@code max-cpus}, unless the identity
 * provider gives the user their own); otherwise it gets the server's defaults ({@code memory-limit}, {@code cpus})
 * lowered to the caps, or the caps, or no limit at all.
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
        return of(memory, cpus, settings, new Caller.Limits(0, positive(settings.maxMemory()),
                settings.maxCpus() > 0 ? settings.maxCpus() : null));
    }

    /** As {@link #of(String, Double, BobCrewProperties.Docker)}, with the user's caps instead of the server's. */
    static Resources of(String memory, Double cpus, BobCrewProperties.Docker settings, Caller.Limits limits) {
        Long maxMemory = limits.maxMemoryBytes();
        Double maxCpus = limits.maxCpus();

        Long memoryBytes;
        if (memory == null || memory.isBlank()) {
            Long byDefault = positive(settings.memoryLimit());
            memoryBytes = lower(byDefault, maxMemory);
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
                throw new ApiException(HttpStatus.BAD_REQUEST, "You may use at most "
                        + maxMemory / (1024 * 1024) + "MB of memory per session on this server");
            }
        }

        Double checkedCpus;
        if (cpus == null) {
            Double byDefault = settings.cpus() > 0 ? Double.valueOf(settings.cpus()) : null;
            checkedCpus = lower(byDefault, maxCpus);
        } else {
            if (cpus.isNaN() || cpus <= 0 || cpus > MAX_CPUS) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "The number of CPUs must be more than 0");
            }
            if (maxCpus != null && cpus > maxCpus) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You may use at most " + maxCpus
                        + " CPUs per session on this server");
            }
            checkedCpus = cpus;
        }
        return new Resources(memoryBytes, checkedCpus);
    }

    /** The lower of two optional values (null: none). */
    private static <T extends Comparable<T>> T lower(T a, T b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Long positive(DataSize size) {
        return size == null || size.toBytes() <= 0 ? null : size.toBytes();
    }
}
