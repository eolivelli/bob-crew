package bobcrew.ui;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Registry of all configured remote bob-crew servers.
 */
@Component
class ServerRegistry {

    private final Map<String, ServerConnection> servers;

    ServerRegistry(UiProperties properties) {
        Map<String, ServerConnection> map = new LinkedHashMap<>();
        if (properties.servers() != null && !properties.servers().isEmpty()) {
            for (Map.Entry<String, UiProperties.ServerConfig> entry : properties.servers().entrySet()) {
                String key = entry.getKey();
                UiProperties.ServerConfig config = entry.getValue();
                String name = config.name() != null && !config.name().isBlank() ? config.name() : key;
                ServerConnection connection = new ServerConnection(name, config.effectiveUrl(), config.token(),
                        config.tokenFile(), config.caFile());
                map.put(connection.name(), connection);
            }
        } else if (properties.serverUrl() != null && !properties.serverUrl().isBlank()) {
            // single server backward compatibility
            ServerConnection connection = new ServerConnection(null, properties.serverUrl(), properties.token(),
                    properties.tokenFile(), properties.caFile());
            map.put(connection.name(), connection);
        }
        if (map.isEmpty()) {
            throw new IllegalStateException("No bobcrew server configured. Set bobcrew.ui.servers.<name>.* "
                    + "or bobcrew.ui.server-url (BOBCREW_SERVER_URL or ~/.bobcrew/ui.properties)");
        }
        this.servers = Collections.unmodifiableMap(map);
    }

    List<ServerConnection> all() {
        return List.copyOf(servers.values());
    }

    ServerConnection get(String name) {
        return servers.get(name);
    }

    ServerConnection defaultServer() {
        return servers.values().iterator().next();
    }
}
