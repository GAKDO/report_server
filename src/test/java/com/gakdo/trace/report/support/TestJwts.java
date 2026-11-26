// 테스트 도구(테스트 자체는 아님): 테스트용 로그인 토큰. 관리자·고객(렌탈 목록 포함)·QR 촬영 폰·부스를 흉내 낸다.

package com.gakdo.trace.report.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** MockMvc용 JWT. auth-server AU1 클레임(role·scope·rentals·rentalId)을 흉내 낸다. */
public final class TestJwts {

    // 테스트 고객 본인의 렌탈 번호.
    public static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    // 다른 고객의 렌탈 번호.
    public static final String OTHER_RENTAL = "Z4U03wInx137yk13jDsmn";
    // 테스트에 쓰는 시점 번호.
    public static final String CHECKPOINT = "IPv6mcQOlTkKj6mOecAQt";
    // 테스트 관리자 ID.
    public static final String ADMIN_ID = "admin0000000000000001";

    // 객체를 만들 일이 없는 도구 모음이라 생성자를 막아 둔다
    private TestJwts() {
    }

    // 테스트 도구: 관리자 토큰을 붙인 요청으로 만든다
    public static JwtRequestPostProcessor admin() {
        // (도구) jwt() = 진짜 서명 없이 "이 토큰으로 로그인한 요청"을 흉내 내는 스프링 시큐리티 테스트 도구
        // 사용자 ID와 역할 ADMIN을 토큰 내용(클레임)에 넣는다
        return jwt().jwt(j -> j.subject(ADMIN_ID).claim("role", "ADMIN"))
                // 스프링 권한 검사에 쓰이는 ROLE_ADMIN 권한을 준다
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    // 테스트 도구: 받은 렌탈 번호들을 가진 고객 토큰을 붙인 요청으로 만든다
    public static JwtRequestPostProcessor customer(String... rentals) {
        // 고객 ID와 역할 CUSTOMER를 넣고
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                // 본인 렌탈 번호 목록(rentals)을 넣는다
                .claim("rentals", List.of(rentals)))
                // ROLE_CUSTOMER 권한을 준다
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** QR 교환으로 받은 촬영 JWT: 리포트는 못 보고 진행률만 */
    public static JwtRequestPostProcessor capture() {
        // 고객 ID와 역할 CUSTOMER를 넣고
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                // 시점 하나(scope)와 렌탈 하나(rentalId)를 넣는다(촬영 JWT 모양)
                .claim("scope", CHECKPOINT).claim("rentalId", RENTAL))
                // ROLE_CUSTOMER 권한을 준다
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // 테스트 도구: 부스 토큰을 붙인 요청으로 만든다
    public static JwtRequestPostProcessor booth() {
        // 부스 ID, 역할 BOOTH, 범위(부스 ID)를 넣는다
        return jwt().jwt(j -> j.subject("booth-01").claim("role", "BOOTH").claim("scope", "booth-01"))
                // ROLE_BOOTH 권한을 준다
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }
}
