package com.gakdo.trace.report.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** MockMvc용 JWT. auth-server AU1 클레임(role·scope·rentals·rentalId)을 흉내 낸다. */
public final class TestJwts {

    public static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    public static final String OTHER_RENTAL = "Z4U03wInx137yk13jDsmn";
    public static final String CHECKPOINT = "IPv6mcQOlTkKj6mOecAQt";
    public static final String ADMIN_ID = "admin0000000000000001";

    private TestJwts() {
    }

    public static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject(ADMIN_ID).claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    public static JwtRequestPostProcessor customer(String... rentals) {
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("rentals", List.of(rentals)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** QR 교환으로 받은 촬영 JWT: 리포트는 못 보고 진행률만 */
    public static JwtRequestPostProcessor capture() {
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("scope", CHECKPOINT).claim("rentalId", RENTAL))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    public static JwtRequestPostProcessor booth() {
        return jwt().jwt(j -> j.subject("booth-01").claim("role", "BOOTH").claim("scope", "booth-01"))
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }
}
