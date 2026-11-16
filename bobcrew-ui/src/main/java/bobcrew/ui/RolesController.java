package bobcrew.ui;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only access to the role library. Roles exist only in the UI app, so these paths are answered here and
 * never forwarded to a server; being more specific than {@code /api/**} is what makes them win over the proxy.
 */
@RestController
class RolesController {

    /** A role in the list: no prompt, so the list stays small. */
    record Summary(String id, String name, String description, String image, List<String> env, String repository,
            String branch) {
    }

    private final RoleRegistry roles;

    RolesController(RoleRegistry roles) {
        this.roles = roles;
    }

    @GetMapping("/api/roles")
    List<Summary> list() {
        return roles.list().stream().map(r -> new Summary(r.id(), r.name(), r.description(), r.image(), r.env(),
                r.repository(), r.branch())).toList();
    }

    @GetMapping("/api/roles/{id}")
    ResponseEntity<?> get(@PathVariable String id) {
        return roles.get(id)
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "No such role")));
    }
}
