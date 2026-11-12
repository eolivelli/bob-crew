package bobcrew.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every request needs a valid bearer token: HS256 signature, not expired, our issuer and audience,
 * a subject, and a {@code jti} that is not in the revocation file. There are no cookies and no
 * server-side login state, so CSRF protection has nothing to protect and is off.
 */
@Configuration
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(requests -> requests
                // lets the error page render the status of a request that was already authorized or refused
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(decoder)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(BobCrewProperties properties) {
        BobCrewProperties.Auth auth = properties.auth();
        byte[] secret = Tokens.readSecret(auth.jwtSecretFile());
        if (Tokens.readableByOthers(auth.jwtSecretFile())) {
            log.warn("The JWT secret file {} is readable by other users; restrict it with chmod 600",
                    auth.jwtSecretFile());
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                JwtValidators.createDefaultWithIssuer(auth.issuer()),
                required(auth.audience()),
                new RevokedTokens(auth.revokedJtiFile()))));
        return decoder;
    }

    /** Requires what the default validators only check when present. */
    private static OAuth2TokenValidator<Jwt> required(String audience) {
        return jwt -> {
            if (jwt.getExpiresAt() == null) {
                return invalid("The token has no expiry");
            }
            if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                return invalid("The token has no subject");
            }
            if (jwt.getAudience() == null || !jwt.getAudience().contains(audience)) {
                return invalid("The token is not for this service");
            }
            return OAuth2TokenValidatorResult.success();
        };
    }

    private static OAuth2TokenValidatorResult invalid(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", description, null));
    }

    /**
     * Rejects tokens whose {@code jti} is listed in a file, one per line ({@code #} starts a
     * comment). The file is read again whenever it changes. If it is configured but cannot be
     * read, every token is rejected rather than silently accepting revoked ones.
     */
    static final class RevokedTokens implements OAuth2TokenValidator<Jwt> {

        private final Path file;

        private FileTime loadedAt;

        private Set<String> revoked = Set.of();

        private boolean unreadable;

        RevokedTokens(String file) {
            this.file = file == null || file.isBlank() ? null : Path.of(file);
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            if (file == null) {
                return OAuth2TokenValidatorResult.success();
            }
            if (jwt.getId() == null) {
                return invalid("The token has no id");
            }
            Set<String> current = load();
            if (current == null) {
                return invalid("The revocation list is unavailable");
            }
            return current.contains(jwt.getId()) ? invalid("The token was revoked")
                    : OAuth2TokenValidatorResult.success();
        }

        private synchronized Set<String> load() {
            try {
                if (!Files.exists(file)) {
                    // nothing revoked yet
                    loadedAt = null;
                    revoked = Set.of();
                    return revoked;
                }
                FileTime modified = Files.getLastModifiedTime(file);
                if (!modified.equals(loadedAt) || unreadable) {
                    revoked = Files.readAllLines(file).stream()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .collect(Collectors.toUnmodifiableSet());
                    loadedAt = modified;
                    unreadable = false;
                }
                return revoked;
            } catch (IOException e) {
                if (!unreadable) {
                    log.error("Cannot read the revocation list {}; rejecting all tokens until it is readable",
                            file, e);
                }
                unreadable = true;
                return null;
            }
        }
    }
}
