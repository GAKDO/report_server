package com.gakdo.trace.report.common.infra;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;

import com.gakdo.trace.report.common.domain.DomainException;
import com.gakdo.trace.report.common.domain.ErrorCode;

/**
 * 요청한 토큰(auth-server AU1 클레임). 렌탈 범위 판단은 여기 한 곳에서 한다.
 * gateway도 rentals 클레임을 보지만 서버가 다시 확인한다(decisions.md 11/2, 헌법 V).
 */
public record Caller(String subject, String role, String scope, List<String> rentals) {

    public static Caller from(Jwt jwt) {
        List<String> rentals = jwt.getClaimAsStringList("rentals");
        return new Caller(jwt.getSubject(), jwt.getClaimAsString("role"), jwt.getClaimAsString("scope"),
                rentals == null ? List.of() : List.copyOf(rentals));
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /** 고객 폰 촬영 JWT(QR 교환, scope = checkpointId). 리포트·체인은 볼 수 없고 진행률(RP2)만. */
    public boolean isCaptureToken() {
        return "CUSTOMER".equals(role) && PublicIds.isValid(scope);
    }

    /** 관리자이거나 rentals 클레임에 그 렌탈이 있는 일반 고객만. */
    public void requireRentalReader(String rentalId) {
        if (isAdmin()) {
            return;
        }
        if (!"CUSTOMER".equals(role) || isCaptureToken()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "리포트는 관리자나 렌탈 고객만 볼 수 있습니다.");
        }
        if (!rentals.contains(rentalId)) {
            throw new DomainException(ErrorCode.OUT_OF_SCOPE, null);
        }
    }
}
