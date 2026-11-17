package bobcrew.server;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import jakarta.servlet.DispatcherType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
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
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every request needs a valid bearer token from an issuer this server trusts, and a caller allowed to use it:
 * <ul>
 * <li>tokens made with {@code issue-token} (when {@code bobcrew.auth.jwt-secret-file} is set): HS256 signature,
 * not expired, our issuer and audience, a subject, and a {@code jti} that is not in the revocation file;</li>
 * <li>tokens from the OpenID Connect provider (when {@code bobcrew.auth.oidc.issuer-uri} is set): signed with one of
 * the provider's published keys, not expired, its issuer, our audience, and a user name.</li>
 * </ul>
 * The token's issuer picks the check; a token from any other issuer is refused. {@link CallerFactory} then decides
 * what the caller may do, and a caller without access gets 403 everywhere. There are no cookies and no
 * server-side login state, so CSRF protection has nothing to protect and is off.
 */
@Configuration
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BobCrewProperties properties, CallerFactory callers)
            throws Exception {
        JwtIssuerAuthenticationManagerResolver resolver = new JwtIssuerAuthenticationManagerResolver(
                issuerManagers(properties.auth(), callers)::get);
        http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(requests -> requests
                // lets the error page render the status of a request that was already authorized or refused
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .anyRequest().hasAuthority(CallerAuthentication.USE))
            .exceptionHandling(exceptions -> exceptions.accessDeniedHandler((request, response, denied) -> {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("application/json");
                response.getWriter().write(JSON.writeValueAsString(Map.of("error",
                        "Your account is not allowed to use bob-crew; ask an administrator for access")));
            }))
            .oauth2ResourceServer(server -> server.authenticationManagerResolver(resolver));
        return http.build();
    }

    /** One authentication manager per trusted issuer. */
    static Map<String, AuthenticationManager> issuerManagers(BobCrewProperties.Auth auth, CallerFactory callers) {
        Map<String, AuthenticationManager> managers = new HashMap<>();
        if (auth.jwtSecretFile() != null && !auth.jwtSecretFile().isBlank()) {
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(tokenDecoder(auth));
            provider.setJwtAuthenticationConverter(jwt -> new CallerAuthentication(jwt, callers.fromToken(jwt)));
            managers.put(auth.issuer(), new ProviderManager(provider));
        }
        BobCrewProperties.Oidc oidc = auth.oidc();
        if (oidc.enabled()) {
            String issuer = oidc.issuerUri().strip();
            if (managers.containsKey(issuer)) {
                throw new IllegalStateException("bobcrew.auth.oidc.issuer-uri must differ from bobcrew.auth.issuer");
            }
            JwtAuthenticationProvider provider = new JwtAuthenticationProvider(oidcDecoder(oidc, issuer));
            provider.setJwtAuthenticationConverter(jwt -> new CallerAuthentication(jwt, callers.fromOidc(jwt)));
            managers.put(issuer, new ProviderManager(provider));
            log.info("Trusting tokens from the identity provider {}", issuer);
        }
        if (managers.isEmpty()) {
            throw new IllegalStateException("No way to check tokens: set bobcrew.auth.oidc.issuer-uri "
                    + "(BOBCREW_OIDC_ISSUER_URI) for an identity provider such as Keycloak, or "
                    + "bobcrew.auth.jwt-secret-file (BOBCREW_JWT_SECRET_FILE) for tokens made with issue-token");
        }
        return Map.copyOf(managers);
    }

    /** Tokens made with {@code issue-token}. */
    static JwtDecoder tokenDecoder(BobCrewProperties.Auth auth) {
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

    /**
     * Tokens from the identity provider. Its configuration and keys are fetched on first use, not at startup, so
     * that the server starts while the provider is down (until then, requests with its tokens fail); a failed
     * fetch is tried again on the next request. New signing keys are picked up as the provider rotates them.
     */
    static JwtDecoder oidcDecoder(BobCrewProperties.Oidc oidc, String issuer) {
        RestOperations rest = restClient(oidc.caFile());
        String usernameClaim = oidc.usernameClaim();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(List.of(
                JwtValidators.createDefaultWithIssuer(issuer),
                jwt -> {
                    if (jwt.getExpiresAt() == null) {
                        return invalid("The token has no expiry");
                    }
                    if (jwt.getAudience() == null || !jwt.getAudience().contains(oidc.audience())) {
                        return invalid("The token is not for this service");
                    }
                    String name = jwt.getClaimAsString(usernameClaim);
                    if (name == null || name.isBlank() || name.length() > 200
                            || name.chars().anyMatch(Character::isISOControl)) {
                        return invalid("The token has no valid " + usernameClaim);
                    }
                    return OAuth2TokenValidatorResult.success();
                }));
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).restOperations(rest).build();
            decoder.setJwtValidator(validator);
            return decoder;
        });
    }

    /** A client for the provider's configuration and keys: the JDK's trust, or exactly the CA file's. */
    private static RestOperations restClient(String caFile) {
        HttpClient.Builder http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER);
        if (caFile != null && !caFile.isBlank()) {
            http.sslContext(trusting(Path.of(caFile.strip())));
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new RestTemplate(factory);
    }

    /** An SSL context that trusts exactly the certificates in the PEM file. */
    static SSLContext trusting(Path caFile) {
        try (InputStream in = Files.newInputStream(caFile)) {
            KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
            trustStore.load(null, null);
            int index = 0;
            for (Certificate certificate : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                trustStore.setCertificateEntry("ca-" + index++, certificate);
            }
            if (index == 0) {
                throw new IllegalStateException("No certificate found in " + caFile);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load the CA file " + caFile + ": " + e, e);
        }
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
