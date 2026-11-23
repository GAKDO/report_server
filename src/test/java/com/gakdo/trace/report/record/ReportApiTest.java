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

@WebMvcTest(controllers = {ReportController.class, VerifyController.class})
class ReportApiTest extends WebTestBase {

    private static final String CP = "aJ3bYet82WYF8iVlHstT2";
    private static final String HASH = "7f3a0c1e5b9d2f4a6c8e0b1d3f5a7c9e1b3d5f7a9c1e3b5d7f9a1c3e5b7de21b";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-12T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ReportQueryService queries;

    @MockitoBean
    private SealService seals;

    private static ChainRecord chainRecord() {
        return new ChainRecord(new ChainRecord.Position(RENTAL, CP, CheckpointType.OUT, 1), Grade.A,
                new ChainRecord.Seal("{}", HASH, null, HASH, "c2ln", AT), false);
    }

    // 도우미: 출고 시점이 등급 A, 배터리 용량 84%(용량 감소 플래그), 새 흠집 핀 1개인 타임라인을 준비한다.
    private void timelineExists() {
        CheckpointView view = new CheckpointView(RENTAL, CP);
        view.assignType(CheckpointType.OUT);
        view.recordGrade("Z4U03wInx137yk13jDsmn", Grade.A, "grade-rule-1.0", List.of(), List.of(), List.of(), AT);
        view.recordDiagnostics("R1n0000000000000000aa", HASH, List.of("CAPACITY_DROP"), 84.0);
        view.recordPins(List.of(new Pin("D1n0000000000000000aa", "TOP", 120.5, 88.0, true)));
        when(queries.timeline(RENTAL)).thenReturn(new ReportQueryService.Timeline(RENTAL, "MBA13-M2", "VALID",
                "https://gakdo.site/verify/0123456789abcdef0123456789abcdef", List.of(view)));
    }

    // [상황] 렌탈 주인인 고객과 관리자가 리포트(타임라인)를 연다.
    // [기대] 기종·체인 상태·검증 주소·시점별 등급·흠집 핀 위치·배터리 최대 용량·진단 플래그가 보인다.
    @Test
    @DisplayName("타임라인: 관리자와 본인 렌탈 고객은 기종·시점별 등급·흠집 핀·배터리 최대 용량·진단 플래그·체인 상태를 본다")
    void timelineForAdminAndOwner() throws Exception {
        timelineExists();
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(customer(RENTAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelCode").value("MBA13-M2"))
                .andExpect(jsonPath("$.chainStatus").value("VALID"))
                .andExpect(jsonPath("$.verifyUrl").value("https://gakdo.site/verify/0123456789abcdef0123456789abcdef"))
                .andExpect(jsonPath("$.checkpoints[0].type").value("OUT"))
                .andExpect(jsonPath("$.checkpoints[0].status").value("GRADED"))
                .andExpect(jsonPath("$.checkpoints[0].grade").value("A"))
                .andExpect(jsonPath("$.checkpoints[0].pins[0].defectId").value("D1n0000000000000000aa"))
                .andExpect(jsonPath("$.checkpoints[0].pins[0].face").value("TOP"))
                .andExpect(jsonPath("$.checkpoints[0].pins[0].x").value(120.5))
                .andExpect(jsonPath("$.checkpoints[0].pins[0].isNew").value(true))
                .andExpect(jsonPath("$.checkpoints[0].diagnosticsSummary.maxCapacityPct").value(84.0))
                .andExpect(jsonPath("$.checkpoints[0].diagnosticsSummary.flags[0]").value("CAPACITY_DROP"))
                .andExpect(jsonPath("$.checkpoints[0].newDefectCount").value(0));
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(admin())).andExpect(status().isOk());
    }

    // [상황] 남의 고객, QR 촬영 폰, 부스, 로그인 안 한 사람이 리포트를 열고, 관리자가 틀린 모양의 렌탈 번호로 연다.
    // [기대] 남의 고객 403(OUT_OF_SCOPE), 촬영 폰·부스 403, 로그인 없음 401, 틀린 번호 400.
    @Test
    @DisplayName("타임라인: 남의 렌탈은 403 OUT_OF_SCOPE, 촬영 JWT·부스는 403 FORBIDDEN, 토큰 없으면 401")
    void timelineScope() throws Exception {
        timelineExists();
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(customer(OTHER_RENTAL)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(capture()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL).with(booth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/reports/rentals/not-a-public-id").with(admin()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // [상황] 관리자와 고객이 체인 전체(서명·이전 해시까지)를 연다.
    // [기대] 관리자만 볼 수 있고 고객은 403.
    @Test
    @DisplayName("체인 전체는 관리자만: 서명·이전 해시까지 보이고 고객은 403")
    void chainIsAdminOnly() throws Exception {
        when(queries.chain(RENTAL)).thenReturn(new ReportQueryService.Chain(RENTAL, true, List.of(chainRecord())));
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/chain").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.records[0].seq").value(1))
                .andExpect(jsonPath("$.records[0].prevHash").isEmpty())
                .andExpect(jsonPath("$.records[0].signature").value("c2ln"));
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/chain").with(customer(RENTAL)))
                .andExpect(status().isForbidden());
    }

    // [상황] 누구나 QR로 여는 공개 검증 페이지를 부른다. 없는 검증 번호, 주소 꼼수(../), 16진수가 아닌 값도 넣는다.
    // [기대] 체인 온전 여부·기종·시점·등급·지문만 주고 서명·시점 번호·렌탈 번호는 없다.
    //        없는 번호는 404, 꼼수·틀린 값도 4xx로 막는다.
    @Test
    @DisplayName("공개 검증은 로그인 없이 등급·시점·지문·서명 시각만 주고, 서명·사진·렌탈 ID는 없다")
    void publicVerifyHasNoDetails() throws Exception {
        String token = "ab".repeat(16);
        when(queries.verify(token)).thenReturn(Optional.of(new ReportQueryService.Verification(true, "MacBook Air (M2, 2022)", List.of(chainRecord()), AT)));
        mvc.perform(get("/api/v1/verify/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.modelName").value("MacBook Air (M2, 2022)"))
                .andExpect(jsonPath("$.records[0].checkpointType").value("OUT"))
                .andExpect(jsonPath("$.records[0].grade").value("A"))
                .andExpect(jsonPath("$.records[0].payloadHash").value(HASH))
                .andExpect(jsonPath("$.records[0].signature").doesNotExist())
                .andExpect(jsonPath("$.records[0].checkpointId").doesNotExist())
                .andExpect(jsonPath("$.rentalId").doesNotExist());
        when(queries.verify("ffffffffffffffffffffffffffffffff")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/verify/ffffffffffffffffffffffffffffffff"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/verify/../reports")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/v1/verify/NOT-HEX")).andExpect(status().isNotFound());
    }

    // [상황] 관리자가 출고 시점을 수동 봉인한다. 고객이 해 보고, 사유를 짧게·공백으로 채워 보고,
    //        XML로 답을 달라 하고, 없는 등급 E를 넣고, 이미 봉인된 시점을 다시 봉인한다.
    // [기대] 관리자 정상 요청은 201. 고객 403, 사유 5자 미만·공백뿐 400, XML 4xx, 등급 E 400,
    //        이미 봉인이면 409 RECORD_ALREADY_SEALED.
    @Test
    @DisplayName("수동 봉인: 관리자만, 사유 5자 이상, 이미 봉인이면 409 RECORD_ALREADY_SEALED")
    void manualSeal() throws Exception {
        String url = "/api/v1/reports/rentals/" + RENTAL + "/checkpoints/" + CP + "/seal";
        String body = "{\"checkpointType\":\"OUT\",\"grade\":\"A\",\"reason\":\"집 PC 꺼짐, 육안 확인\"}";
        when(seals.sealManually(eq(RENTAL), eq(CP), eq(CheckpointType.OUT), eq(Grade.A), anyString(), anyString()))
                .thenReturn(chainRecord());
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.seq").value(1));

        mvc.perform(post(url).with(customer(RENTAL)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkpointType\":\"OUT\",\"grade\":\"A\",\"reason\":\"짧음\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkpointType\":\"OUT\",\"grade\":\"A\",\"reason\":\"     a     \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_XML)
                .content(body))
                .andExpect(status().is4xxClientError());
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkpointType\":\"OUT\",\"grade\":\"E\",\"reason\":\"다섯 글자 이상\"}"))
                .andExpect(status().isBadRequest());

        when(seals.sealManually(any(), any(), any(), any(), any(), any()))
                .thenThrow(new DomainException(ErrorCode.RECORD_ALREADY_SEALED, null));
        mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_ALREADY_SEALED"));
    }

    // [상황] 관리자와 고객이 수동 봉인 사용 기록을 본다. 한 번에 0개를 달라고도 해 본다.
    // [기대] 관리자만 볼 수 있고, 개수는 1~100만 받는다.
    @Test
    @DisplayName("수동 봉인 사용 기록은 관리자만, limit은 1~100")
    void manualSealLog() throws Exception {
        when(queries.manualSeals(20, null)).thenReturn(new ReportQueryService.ManualSeals(List.of(), null));
        mvc.perform(get("/api/v1/reports/manual-seals").with(admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isArray());
        mvc.perform(get("/api/v1/reports/manual-seals?limit=0").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/manual-seals").with(customer(RENTAL))).andExpect(status().isForbidden());
        verifyNoInteractions(seals);
    }

    // [상황] 관리자가 일련번호 앞부분 "C02"로 기록을 검색한다. 한 글자 검색, 고객 검색도 해 본다.
    // [기대] 일련번호·체인 상태가 담긴 결과를 준다. 한 글자는 400, 고객은 403.
    @Test
    @DisplayName("기록 검색은 관리자만, 검색어 2~64자, 결과는 계약 ReportSearchPage 모양")
    void searchReports() throws Exception {
        when(queries.search("C02", 20, null)).thenReturn(new ReportQueryService.SearchPage(List.of(
                new ReportQueryService.SearchHit(RENTAL, "C02EXAMPLE01", "MBA13-M2", "VALID", AT)), null));
        mvc.perform(get("/api/v1/reports/search?q=C02").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].serial").value("C02EXAMPLE01"))
                .andExpect(jsonPath("$.items[0].chainStatus").value("VALID"));
        mvc.perform(get("/api/v1/reports/search?q=C").with(admin())).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/reports/search?q=C02").with(customer(RENTAL))).andExpect(status().isForbidden());
    }
}
