package com.gakdo.trace.report.common.infra;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;

import com.gakdo.trace.report.common.domain.DomainException;
import com.gakdo.trace.report.common.domain.ErrorCode;

/**
 * 요청한 토큰(auth-server AU1 클레임). 렌탈 범위 판단은 여기 한 곳에서 한다.
 * gateway도 rentals 클레임을 보지만 서버가 다시 확인한다(decisions.md 11/2, 헌법 V).
 */
public record Caller(String subject, String role, String scope, List<String> rentals, String rentalId) {

    public static Caller from(Jwt jwt) {
        List<String> rentals = jwt.getClaimAsStringList("rentals");
        return new Caller(jwt.getSubject(), jwt.getClaimAsString("role"), jwt.getClaimAsString("scope"),
                rentals == null ? List.of() : List.copyOf(rentals), jwt.getClaimAsString("rentalId"));
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    /** 고객 폰 촬영 JWT(QR 교환, scope = checkpointId). 리포트·체인은 볼 수 없고 진행률(RP2)만. */
    public boolean isCaptureToken() {
        return "CUSTOMER".equals(role) && PublicIds.isValid(scope);
    }

    /**
     * 진행률(RP2): 관리자·렌탈 고객은 렌탈 전체, 촬영 JWT는 그 렌탈(rentalId 클레임)의 자기 시점(scope)만.
     *
     * @return 촬영 JWT면 볼 수 있는 checkpointId, 아니면 null(렌탈 전체)
     */
    public String requireProgressReader(String rentalId) {
        if (isCaptureToken()) {
            if (!rentalId.equals(this.rentalId)) {
                throw new DomainException(ErrorCode.OUT_OF_SCOPE, null);
            }
            return scope;
        }
        requireRentalReader(rentalId);
        return null;
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
