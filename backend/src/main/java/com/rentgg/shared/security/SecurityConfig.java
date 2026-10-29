package com.rentgg.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.rentgg.config.RentGgProperties;
import com.rentgg.shared.error.ErrorResponse;
import com.rentgg.shared.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final String API = "/api/v1";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper,
                                            PermissionJwtConverter jwtConverter) throws Exception {
        http
            .csrf(c -> c.disable())
            .cors(c -> {})
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(h -> h.httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true)))
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, API + "/auth/register", API + "/auth/login", API + "/auth/refresh",
                        API + "/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, API + "/games", API + "/games/*", API + "/settings/public").permitAll()
                .requestMatchers(HttpMethod.GET, API + "/listings/search", API + "/listings/*",
                        API + "/listings/*/images/*").permitAll()
                .requestMatchers(HttpMethod.POST, API + "/payments/webhook/*").permitAll()
                // VNPay IPN is a GET; the return URL is the payer's browser coming back (no token).
                .requestMatchers(HttpMethod.GET, API + "/payments/webhook/*", API + "/payments/return/*").permitAll()
                .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info", "/actuator/prometheus").permitAll()
                .requestMatchers("/error").permitAll()
                // Staff areas (/admin, /support, /finance): every endpoint declares its own permission with
                // @PreAuthorize (enforced by AuthorizationCoverageTest), so here we only require a logged-in caller.
                .requestMatchers(API + "/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.jwtAuthenticationConverter(jwtConverter))
                .authenticationEntryPoint(entryPoint(mapper))
                .accessDeniedHandler(accessDenied(mapper)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(entryPoint(mapper))
                .accessDeniedHandler(accessDenied(mapper)));
        return http.build();
    }

    /** Roles and permissions come from the database per request (cached), not from the token claims. */
    @Bean
    PermissionJwtConverter permissionJwtConverter(ObjectProvider<AuthorityResolver> resolver) {
        return new PermissionJwtConverter(resolver);
    }

    @Bean
    SecretKey jwtSecretKey(RentGgProperties props) {
        byte[] bytes = Base64.getDecoder().decode(props.security().jwtSecret());
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator("rentgg")));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(RentGgProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        String origins = props.security().corsAllowedOrigins();
        List<String> allowed = origins == null || origins.isBlank() ? List.of()
                : Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        cfg.setAllowedOrigins(allowed);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cfg.setExposedHeaders(List.of("X-Trace-Id", "Retry-After"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }

    private AuthenticationEntryPoint entryPoint(ObjectMapper mapper) {
        return (request, response, ex) -> {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            String code = msg.contains("expired") ? "TOKEN_EXPIRED" : "UNAUTHENTICATED";
            String message = "TOKEN_EXPIRED".equals(code)
                    ? "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại."
                    : "Bạn cần đăng nhập.";
            write(mapper, response, HttpServletResponse.SC_UNAUTHORIZED, code, message);
        };
    }

    private AccessDeniedHandler accessDenied(ObjectMapper mapper) {
        return (request, response, ex) ->
                write(mapper, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Bạn không có quyền thực hiện thao tác này.");
    }

    private static void write(ObjectMapper mapper, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message, Map.of(), MDC.get(TraceIdFilter.MDC_KEY)));
    }
}
