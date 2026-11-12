package bobcrew.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The sessions of this server, all in memory: they end with the server, as their agent processes
 * do. This is the only entry point the API uses.
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    private final BobCrewProperties properties;

    private final Workspaces workspaces;

    private final SessionAccessPolicy accessPolicy;

    private volatile boolean stopping;

    SessionManager(BobCrewProperties properties, Workspaces workspaces, SessionAccessPolicy accessPolicy) {
        this.properties = properties;
        this.workspaces = workspaces;
        this.accessPolicy = accessPolicy;
        log.info("Workspace root: {}; agent: {}", workspaces.root(), properties.agent());
    }

    /**
     * Creates a session owned by {@code owner} in the named workspace and starts its agent.
     * @throws ApiException 400 for a bad workspace, 429 when the server is full, 502 if the agent fails
     */
    AgentSession create(String owner, String workspaceName, String title) {
        Path workspace = workspaces.resolve(workspaceName);
        String name = workspaces.nameOf(workspace);
        AgentSession session = new AgentSession(UUID.randomUUID().toString(), owner,
                title == null || title.isBlank() ? name : title.strip(), workspace, name, properties);
        synchronized (this) {
            if (stopping) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "The server is shutting down");
            }
            long active = sessions.values().stream().filter(SessionManager::isActive).count();
            if (active >= properties.maxSessions()) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The server already runs "
                        + properties.maxSessions() + " sessions; close one first");
            }
            sessions.put(session.id(), session);
        }
        try {
            session.start();
        } catch (RuntimeException e) {
            sessions.remove(session.id());
            throw e;
        }
        log.info("Session {} created by {} in workspace {}", session.id(), owner, name);
        return session;
    }

    /** @throws ApiException 404 if there is no such session or the user may not use it */
    AgentSession get(String user, String id) {
        AgentSession session = sessions.get(id);
        if (session == null || !accessPolicy.canAccess(user, session)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No such session");
        }
        return session;
    }

    List<AgentSession.Info> list(String user) {
        return sessions.values()
            .stream()
            .filter(session -> accessPolicy.canAccess(user, session))
            .map(AgentSession::info)
            .sorted(Comparator.comparing(AgentSession.Info::createdAt).reversed())
            .toList();
    }

    /** Closes the session, ends its agent process and forgets it. */
    void close(String user, String id) {
        AgentSession session = get(user, id);
        session.close();
        sessions.remove(id);
        log.info("Session {} closed by {}", id, user);
    }

    List<String> workspaces() {
        return workspaces.list();
    }

    @Scheduled(fixedDelay = 60_000)
    void closeIdleSessions() {
        Duration limit = properties.idleTimeout();
        if (limit == null || limit.isZero() || limit.isNegative()) {
            return;
        }
        Instant threshold = Instant.now().minus(limit);
        for (AgentSession session : sessions.values()) {
            if (session.status() != AgentSession.Status.RUNNING && session.updatedAt().isBefore(threshold)) {
                log.info("Session {} closed after {} without activity", session.id(), limit);
                session.close();
                sessions.remove(session.id());
            }
        }
    }

    /**
     * Ends every agent process when the application stops. This runs before the web server's
     * graceful shutdown, and it ends the open event streams, which would otherwise hold it up.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        synchronized (this) {
            stopping = true;
        }
        sessions.values().parallelStream().forEach(AgentSession::close);
        sessions.clear();
    }

    private static boolean isActive(AgentSession session) {
        return switch (session.status()) {
            case STARTING, IDLE, RUNNING -> true;
            case FAILED, CLOSED -> false;
        };
    }
}
