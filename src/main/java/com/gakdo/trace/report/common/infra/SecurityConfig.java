package com.gakdo.trace.report.common.infra;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.report.common.api.ProblemFactory;
import com.gakdo.trace.report.common.domain.ErrorCode;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 역할×경로 규칙(contracts x-trace-access). JWT는 auth-server JWKS로 RS256 검증하고 role 클레임을 ROLE_*로 쓴다.
 * 렌탈 범위(본인 렌탈)는 컨트롤러가 Caller로 다시 본다.
 */
@Configuration
public class SecurityConfig {

    private static final String ADMIN = "ADMIN";
    private static final String CUSTOMER = "CUSTOMER";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // SSE가 끝날 때 오는 비동기 재디스패치는 처음 요청에서 이미 검사했다
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/reports/ping", "/api/v1/verify/*").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/reports/rentals/*/chain", "/api/v1/reports/manual-seals")
                        .hasRole(ADMIN)
                        .requestMatchers(HttpMethod.POST, "/api/v1/reports/rentals/*/checkpoints/*/seal").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.GET, "/api/v1/reports/rentals/*").hasAnyRole(ADMIN, CUSTOMER)
                        .requestMatchers(HttpMethod.GET, "/api/v1/reports/rentals/*/stream").hasAnyRole(ADMIN, CUSTOMER)
                        // 검색(선택)은 아직 없다
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roleConverter()))
                        .authenticationEntryPoint((req, res, e) -> write(objectMapper, req, res, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, e) -> write(objectMapper, req, res, ErrorCode.FORBIDDEN)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> write(objectMapper, req, res, ErrorCode.UNAUTHENTICATED))
                        .accessDeniedHandler((req, res, ex) -> write(objectMapper, req, res, ErrorCode.FORBIDDEN)));
        return http.build();
    }

    /** JWKS 주소는 spring.security.oauth2.resourceserver.jwt.jwk-set-uri, 발급자는 trace. 키는 첫 요청 때 받는다. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${app.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer)));
        return decoder;
    }

    static JwtAuthenticationConverter roleConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        });
        return converter;
    }

    private static void write(ObjectMapper objectMapper, HttpServletRequest request, HttpServletResponse response,
            ErrorCode code) throws IOException {
        ProblemDetail problem = ProblemFactory.of(code, null, request.getRequestURI(), List.of());
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
