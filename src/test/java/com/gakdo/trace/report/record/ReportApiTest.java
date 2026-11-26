// 이 파일이 확인하는 것: 렌탈 리포트 화면에 쓰이는 API들(타임라인·체인·공개 검증·수동 봉인·검색)과 볼 수 있는 사람.
// DB 대신 가짜 조회 서비스를 쓰고, 보안 규칙과 오류 형식은 운영과 같다.

package com.gakdo.trace.report.record;

import static com.gakdo.trace.report.support.TestJwts.OTHER_RENTAL;
import static com.gakdo.trace.report.support.TestJwts.RENTAL;
import static com.gakdo.trace.report.support.TestJwts.admin;
import static com.gakdo.trace.report.support.TestJwts.booth;
import static com.gakdo.trace.report.support.TestJwts.capture;
import static com.gakdo.trace.report.support.TestJwts.customer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.report.common.domain.DomainException;
import com.gakdo.trace.report.common.domain.ErrorCode;
import com.gakdo.trace.report.record.api.ReportController;
import com.gakdo.trace.report.record.api.VerifyController;
import com.gakdo.trace.report.record.application.ReportQueryService;
import com.gakdo.trace.report.record.application.SealService;
import com.gakdo.trace.report.record.domain.ChainRecord;
import com.gakdo.trace.report.record.domain.CheckpointType;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.domain.Pin;
import com.gakdo.trace.report.support.WebTestBase;

// (도구) @WebMvcTest = 서버 전체가 아니라 지정한 컨트롤러(리포트·공개 검증)만 띄워 빠르게 시험한다
@WebMvcTest(controllers = {ReportController.class, VerifyController.class})
// WebTestBase를 이어받아 운영과 같은 보안 규칙·오류 형식으로 시험한다
class ReportApiTest extends WebTestBase {

    // 테스트에 쓰는 시점 번호, 64자 해시, 시각
    private static final String CP = "aJ3bYet82WYF8iVlHstT2";
    private static final String HASH = "7f3a0c1e5b9d2f4a6c8e0b1d3f5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b7de21b";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-12T00:00:00Z");

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) MockMvc = 서버를 실제로 띄우지 않고 HTTP 요청을 흉내 내 컨트롤러를 부르는 도구
    @Autowired
    private MockMvc mvc;

    // (도구) @MockitoBean = 스프링 안의 진짜 객체를 가짜(mock)로 바꿔 끼운다. mock은 정해 준 대로만 답하는 대역이다
    // 조회 서비스를 가짜로 바꿔 DB 없이 결과를 정한다
    @MockitoBean
    private ReportQueryService queries;

    // 봉인 서비스도 가짜로 바꾼다
    @MockitoBean
    private SealService seals;

    // 테스트 도구: 출고 1번, 등급 A인 체인 기록 하나를 만든다
    private static ChainRecord chainRecord() {
        // 위치(렌탈·시점·출고·1번)와 봉인 정보(내용 {}·해시·앞 해시 없음·서명 c2ln·시각)로 만든다
        return new ChainRecord(new ChainRecord.Position(RENTAL, CP, CheckpointType.OUT, 1), Grade.A,
                new ChainRecord.Seal("{}", HASH, null, HASH, "c2ln", AT), false);
    }

    // 도우미: 출고 시점이 등급 A, 배터리 용량 84%(용량 감소 플래그), 새 흠집 핀 1개인 타임라인을 준비한다.
    private void timelineExists() {
        // 시점을 만들고
        CheckpointView view = new CheckpointView(RENTAL, CP);
        // 종류를 출고로 정하고
        view.assignType(CheckpointType.OUT);
        // 등급 A를 적용하고
        view.recordGrade("Z4U03wInx137yk13jDsmn", Grade.A, "grade-rule-1.0", List.of(), List.of(), List.of(), AT);
        // 진단 결과(최대 용량 84%, 용량 감소 플래그)를 적용하고
        view.recordDiagnostics("R1n0000000000000000aa", HASH, List.of("CAPACITY_DROP"), 84.0);
        // 새 흠집 핀 1개(윗면 x 120.5, y 88)를 적용한다
        view.recordPins(List.of(new Pin("D1n0000000000000000aa", "TOP", 120.5, 88.0, true)));
        // (도구) when(...).thenReturn(값) = 그 메서드가 불리면 이 값을 돌려주라고 가짜에게 정해 둔다
        // 타임라인을 물으면 기종·체인 상태·검증 주소·이 시점을 담아 돌려주게 한다
        when(queries.timeline(RENTAL)).thenReturn(new ReportQueryService.Timeline(RENTAL, "MBA13-M2", "VALID",
                "https://gakdo.site/verify/0123456789abcdef0123456789abcdef", List.of(view)));
    }

    // [상황] 렌탈 주인인 고객과 관리자가 리포트(타임라인)를 연다.
    // [기대] 기종·체인 상태·검증 주소·시점별 등급·흠집 핀 위치·배터리 최대 용량·진단 플래그가 보인다.
    @Test
    @DisplayName("타임라인: 관리자와 본인 렌탈 고객은 기종·시점별 등급·흠집 핀·배터리 최대 용량·진단 플래그·체인 상태를 본다")
    void timelineForAdminAndOwner() throws Exception {
        // 준비: 타임라인을 준비한다
        timelineExists();
        // (도구) mvc.perform(...) = 만든 요청을 컨트롤러에 보내고, 결과(응답)를 확인할 수 있는 객체를 돌려준다
        // 실행: 렌탈 주인 고객이 리포트를 연다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(customer(RENTAL)))
                // (도구) andExpect(...) = 응답이 기대와 같은지 확인하고, 다르면 테스트를 실패시킨다
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // (도구) jsonPath("$.modelCode") = 응답 JSON에서 modelCode 칸을 가리킨다. value(...)로 그 값이 맞는지 본다
                // 기종 코드가 보이는지 확인한다
                .andExpect(jsonPath("$.modelCode").value("MBA13-M2"))
                // 체인 상태가 VALID인지 확인한다
                .andExpect(jsonPath("$.chainStatus").value("VALID"))
                // 공개 검증 주소가 보이는지 확인한다
                .andExpect(jsonPath("$.verifyUrl").value("https://gakdo.site/verify/0123456789abcdef0123456789abcdef"))
                // 첫 시점 종류가 출고인지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].type").value("OUT"))
                // 상태가 "등급 매김"인지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].status").value("GRADED"))
                // 등급이 A인지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].grade").value("A"))
                // 핀의 흠집 ID가 보이는지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].pins[0].defectId").value("D1n0000000000000000aa"))
                // 핀의 면이 윗면인지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].pins[0].face").value("TOP"))
                // 핀의 x 좌표가 맞는지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].pins[0].x").value(120.5))
                // 새 흠집 표시가 맞는지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].pins[0].isNew").value(true))
                // 배터리 최대 용량 84%가 보이는지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].diagnosticsSummary.maxCapacityPct").value(84.0))
                // 진단 플래그(용량 감소)가 보이는지 확인한다
                .andExpect(jsonPath("$.checkpoints[0].diagnosticsSummary.flags[0]").value("CAPACITY_DROP"))
                // 새 흠집 수 칸이 0으로 오는지 확인한다(등급 이벤트의 새 흠집 목록이 비어 있어서)
                .andExpect(jsonPath("$.checkpoints[0].newDefectCount").value(0));
        // 관리자로 열어도 200인지 확인한다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(admin())).andExpect(status().isOk());
    }

    // [상황] 남의 고객, QR 촬영 폰, 부스, 로그인 안 한 사람이 리포트를 열고, 관리자가 틀린 모양의 렌탈 번호로 연다.
    // [기대] 남의 고객 403(OUT_OF_SCOPE), 촬영 폰·부스 403, 로그인 없음 401, 틀린 번호 400.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 401 = 로그인 필요(토큰이 없거나 만료·위조됨),
    //          400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("타임라인: 남의 렌탈은 403 OUT_OF_SCOPE, 촬영 JWT·부스는 403 FORBIDDEN, 토큰 없으면 401")
    void timelineScope() throws Exception {
        // 준비: 타임라인을 준비한다
        timelineExists();
        // 실행+확인: 남의 고객이 연다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(customer(OTHER_RENTAL)))
                // 403이고 오류 코드가 "범위 밖"인지 확인한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // QR 촬영 폰(촬영 JWT)으로 연다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(capture()))
                // 403 FORBIDDEN인지 확인한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 부스 토큰으로 연다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(booth()))
                // 403 FORBIDDEN인지 확인한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 토큰 없이 연다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL))
                // 401 UNAUTHENTICATED인지 확인한다
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        // 관리자가 모양이 틀린 렌탈 번호로 연다
        mvc.perform(get("/api/v1/reports/rentals/not-a-public-id").with(admin()))
                // 400 VALIDATION_FAILED인지 확인한다
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // [상황] 관리자와 고객이 체인 전체(서명·이전 해시까지)를 연다.
    // [기대] 관리자만 볼 수 있고 고객은 403.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("체인 전체는 관리자만: 서명·이전 해시까지 보이고 고객은 403")
    void chainIsAdminOnly() throws Exception {
        // 준비: 체인을 물으면 온전한(true) 기록 1개짜리 체인을 돌려주게 한다
        when(queries.chain(RENTAL)).thenReturn(new ReportQueryService.Chain(RENTAL, true, List.of(chainRecord())));
        // 실행: 관리자가 체인 전체를 연다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/chain").with(admin()))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 체인이 온전하다고 나오는지 확인한다
                .andExpect(jsonPath("$.valid").value(true))
                // 첫 기록 순번이 1인지 확인한다
                .andExpect(jsonPath("$.records[0].seq").value(1))
                // 첫 기록이라 앞 기록 해시가 비어 있는지 확인한다
                .andExpect(jsonPath("$.records[0].prevHash").isEmpty())
                // 서명 값이 보이는지 확인한다
                .andExpect(jsonPath("$.records[0].signature").value("c2ln"));
        // 렌탈 주인 고객이 체인을 열면
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/chain").with(customer(RENTAL)))
                // 403인지 확인한다
                .andExpect(status().isForbidden());
    }

    // [상황] 누구나 QR로 여는 공개 검증 페이지를 부른다. 없는 검증 번호, 주소 꼼수(../), 16진수가 아닌 값도 넣는다.
    // [기대] 체인 온전 여부·기종·시점·등급·지문만 주고 서명·시점 번호·렌탈 번호는 없다.
    //        없는 번호는 404, 꼼수·틀린 값도 4xx로 막는다.
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터), 4xx = 요청 쪽 문제로 거절(400번대 오류)
    @Test
    @DisplayName("공개 검증은 로그인 없이 등급·시점·지문·서명 시각만 주고, 서명·사진·렌탈 ID는 없다")
    void publicVerifyHasNoDetails() throws Exception {
        // 준비: 32자 검증 번호를 만든다
        String token = "ab".repeat(16);
        // 그 번호로 검증하면 온전함·기종·기록 1개·시각을 돌려주게 한다
        when(queries.verify(token)).thenReturn(Optional.of(new ReportQueryService.Verification(true, "MacBook Air (M2, 2022)", List.of(chainRecord()), AT)));
        // 실행: 로그인 없이 공개 검증 주소를 연다
        mvc.perform(get("/api/v1/verify/" + token))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 체인이 온전하다고 나오는지 확인한다
                .andExpect(jsonPath("$.valid").value(true))
                // 기종 이름이 보이는지 확인한다
                .andExpect(jsonPath("$.modelName").value("MacBook Air (M2, 2022)"))
                // 시점 종류가 보이는지 확인한다
                .andExpect(jsonPath("$.records[0].checkpointType").value("OUT"))
                // 등급이 보이는지 확인한다
                .andExpect(jsonPath("$.records[0].grade").value("A"))
                // 지문(봉인 내용 해시)이 보이는지 확인한다
                .andExpect(jsonPath("$.records[0].payloadHash").value(HASH))
                // 서명은 칸 자체가 없는지 확인한다
                .andExpect(jsonPath("$.records[0].signature").doesNotExist())
                // 시점 번호도 없는지 확인한다
                .andExpect(jsonPath("$.records[0].checkpointId").doesNotExist())
                // 렌탈 번호도 없는지 확인한다(누가 빌렸는지 알 수 없게)
                .andExpect(jsonPath("$.rentalId").doesNotExist());
        // 없는 검증 번호면 빈 값을 돌려주게 한다
        when(queries.verify("ffffffffffffffffffffffffffffffff")).thenReturn(Optional.empty());
        // 없는 번호로 열면
        mvc.perform(get("/api/v1/verify/ffffffffffffffffffffffffffffffff"))
                // 404 NOT_FOUND인지 확인한다
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        // 주소 꼼수(../)는 4xx로 막히는지 확인한다
        mvc.perform(get("/api/v1/verify/../reports")).andExpect(status().is4xxClientError());
        // 16진수가 아닌 값은 404인지 확인한다
        mvc.perform(get("/api/v1/verify/NOT-HEX")).andExpect(status().isNotFound());
    }

    // [상황] 관리자가 출고 시점을 수동 봉인한다. 고객이 해 보고, 사유를 짧게·공백으로 채워 보고,
    //        XML로 답을 달라 하고, 없는 등급 E를 넣고, 이미 봉인된 시점을 다시 봉인한다.
    // [기대] 관리자 정상 요청은 201. 고객 403, 사유 5자 미만·공백뿐 400, XML 4xx, 등급 E 400,
    //        이미 봉인이면 409 RECORD_ALREADY_SEALED.
    //        ※ 응답 코드: 201 = 새로 만들어짐, 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 400 = 요청 내용이 형식에 맞지 않음,
    //          4xx = 요청 쪽 문제로 거절(400번대 오류), 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("수동 봉인: 관리자만, 사유 5자 이상, 이미 봉인이면 409 RECORD_ALREADY_SEALED")
    void manualSeal() throws Exception {
        // 준비: 출고 시점 수동 봉인 주소를 만든다
        String url = "/api/v1/reports/rentals/" + RENTAL + "/checkpoints/" + CP + "/seal";
        // 정상 요청 본문(출고·등급 A·사유)을 만든다
        String body = "{\"checkpointType\":\"OUT\",\"grade\":\"A\",\"reason\":\"집 PC 꺼짐, 육안 확인\"}";
        // (도구) eq(값) = 정확히 그 값일 때만, anyString() = 어떤 글자든 상관없다는 뜻
        // 그 렌탈·시점·출고·등급 A로 봉인하면 체인 기록을 돌려주게 한다
        when(seals.sealManually(eq(RENTAL), eq(CP), eq(CheckpointType.OUT), eq(Grade.A), anyString(), anyString()))
                .thenReturn(chainRecord());
        // 실행+확인: 관리자가 정상 요청을 보낸다
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                // 201이고 기록 순번이 1인지 확인한다
                .andExpect(status().isCreated()).andExpect(jsonPath("$.seq").value(1));

        // 고객이 같은 요청을 보내면
        mvc.perform(post(url).with(customer(RENTAL)).contentType(MediaType.APPLICATION_JSON).content(body))
                // 403인지 확인한다
                .andExpect(status().isForbidden());
        // 사유가 "짧음"(2자)인 요청을 보낸다
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                // 사유를 2자짜리 "짧음"으로 넣은 본문을 싣는다
                .content("{\"checkpointType\":\"OUT\",\"grade\":\"A\",\"reason\":\"짧음\"}"))
                // 400이고 틀린 칸이 reason인지 확인한다
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        // 사유가 공백과 글자 하나뿐인 요청을 보낸다
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                // 사유를 공백과 글자 하나로 채운 본문을 싣는다
                .content("{\"checkpointType\":\"OUT\",\"grade\":\"A\",\"reason\":\"     a     \"}"))
                // 400이고 틀린 칸이 reason인지 확인한다
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        // XML로 답을 달라는 요청을 보낸다
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML)
                // 정상 본문을 싣는다(형식만 XML로 달라고 한 경우)
                .content(body))
                // 4xx로 거절되는지 확인한다
                .andExpect(status().is4xxClientError());
        // 없는 등급 E로 요청을 보낸다
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                // 등급을 E로 넣은 본문을 싣는다
                .content("{\"checkpointType\":\"OUT\",\"grade\":\"E\",\"reason\":\"다섯 글자 이상\"}"))
                // 400인지 확인한다
                .andExpect(status().isBadRequest());

        // 이번엔 봉인하려 하면
        when(seals.sealManually(any(), any(), any(), any(), any(), any()))
                // (도구) thenThrow(오류) = 그 메서드가 불리면 이 오류를 던지라고 가짜에게 정해 둔다
                // "이미 봉인됨" 오류를 던지게 한다
                .thenThrow(new DomainException(ErrorCode.RECORD_ALREADY_SEALED, null));
        // 관리자가 다시 봉인 요청을 보내면
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                // 409이고 오류 코드가 "이미 봉인됨"인지 확인한다
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_ALREADY_SEALED"));
    }

    // [상황] 관리자와 고객이 수동 봉인 사용 기록을 본다. 한 번에 0개를 달라고도 해 본다.
    // [기대] 관리자만 볼 수 있고, 개수는 1~100만 받는다.
    @Test
    @DisplayName("수동 봉인 사용 기록은 관리자만, limit은 1~100")
    void manualSealLog() throws Exception {
        // 준비: 기본 개수(20)로 사용 기록을 물으면 빈 목록을 돌려주게 한다
        when(queries.manualSeals(20, null)).thenReturn(new ReportQueryService.ManualSeals(List.of(), null));
        // 실행+확인: 관리자가 사용 기록을 연다
        mvc.perform(get("/api/v1/reports/manual-seals").with(admin()))
                // 200이고 items가 목록인지 확인한다
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        // 개수 0을 달라고 하면 400인지 확인한다
        mvc.perform(get("/api/v1/reports/manual-seals?limit=0").with(admin())).andExpect(status().isBadRequest());
        // 고객이 열면 403인지 확인한다
        mvc.perform(get("/api/v1/reports/manual-seals").with(customer(RENTAL))).andExpect(status().isForbidden());
        // (도구) verifyNoInteractions(가짜) = 그 가짜가 한 번도 불리지 않았는지 확인한다
        // 기록을 보는 동안 봉인 서비스는 전혀 불리지 않았는지 확인한다
        verifyNoInteractions(seals);
    }

    // [상황] 관리자가 일련번호 앞부분 "C02"로 기록을 검색한다. 한 글자 검색, 고객 검색도 해 본다.
    // [기대] 일련번호·체인 상태가 담긴 결과를 준다. 한 글자는 400, 고객은 403.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("기록 검색은 관리자만, 검색어 2~64자, 결과는 계약 ReportSearchPage 모양")
    void searchReports() throws Exception {
        // 준비: "C02"로 검색하면 일련번호 C02EXAMPLE01 결과 하나를 돌려주게 한다
        when(queries.search("C02", 20, null)).thenReturn(new ReportQueryService.SearchPage(List.of(
                new ReportQueryService.SearchHit(RENTAL, "C02EXAMPLE01", "MBA13-M2", "VALID", AT)), null));
        // 실행: 관리자가 "C02"로 검색한다
        mvc.perform(get("/api/v1/reports/search?q=C02").with(admin()))
                // 확인: 200(성공)인지 확인한다
                .andExpect(status().isOk())
                // 결과의 일련번호가 맞는지 확인한다
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"))
                // 결과의 체인 상태가 VALID인지 확인한다
                .andExpect(jsonPath("$.items[0].chainStatus").value("VALID"));
        // 한 글자 검색은 400인지 확인한다
        mvc.perform(get("/api/v1/reports/search?q=C").with(admin())).andExpect(status().isBadRequest());
        // 고객이 검색하면 403인지 확인한다
        mvc.perform(get("/api/v1/reports/search?q=C02").with(customer(RENTAL))).andExpect(status().isForbidden());
    }
}
