package bobcrew.server;

import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.springframework.http.HttpStatus;

/**
 * What a session's agent may do beyond running commands in its container, each allowed separately by the server
 * ({@code bobcrew.allowed-capabilities}) and asked for by the session:
 * <ul>
 * <li>{@link #DOCKER}: a Docker Engine of its own, inside the container;</li>
 * <li>{@link #LIBVIRT}: virtual machines with libvirt and QEMU/KVM (what Vagrant's libvirt provider uses).</li>
 * </ul>
 * Either one makes the container privileged and started as root, so that the image's
 * {@code /etc/bobcrew/root.d} scripts can start the daemons asked for (they read {@code BOBCREW_CAPABILITIES});
 * the agent itself still runs as {@code bobcrew.docker.user}. Whoever uses such a session can become root on
 * the Docker host.
 */
final class Capabilities {

    static final String DOCKER = "docker";

    static final String LIBVIRT = "libvirt";

    static final List<String> KNOWN = List.of(DOCKER, LIBVIRT);

    private Capabilities() {
    }

    /** The server's allowed capabilities, checked at startup. */
    static List<String> allowed(List<String> configured) {
        if (configured == null) {
            return List.of();
        }
        TreeSet<String> allowed = new TreeSet<>();
        for (String name : configured) {
            String normalized = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            if (!KNOWN.contains(normalized)) {
                throw new IllegalStateException("bobcrew.allowed-capabilities: unknown capability '" + name
                        + "' (known: " + String.join(", ", KNOWN) + ")");
            }
            allowed.add(normalized);
        }
        return List.copyOf(allowed);
    }

    /**
     * @return the requested capabilities, sorted, without duplicates; empty for none
     * @throws ApiException 400 for an unknown capability, 403 for one the server does not allow
     */
    static List<String> check(List<String> requested, List<String> allowed) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        TreeSet<String> checked = new TreeSet<>();
        for (String name : requested) {
            if (name == null || !KNOWN.contains(name)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown capability: " + name + " (known: "
                        + String.join(", ", KNOWN) + ")");
            }
            if (!allowed.contains(name)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "This server does not allow the " + name
                        + " capability");
            }
            checked.add(name);
        }
        return List.copyOf(checked);
    }
}
