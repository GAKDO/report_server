// 이 파일이 확인하는 것: 검사 진행률 실시간 알림(SSE, /reports/rentals/{렌탈}/stream).
// 부스에서 사진을 찍고 나면 고객 폰·웹 화면에 "업로드 20% → 정합 40% → 검사 60% → 등급 80% → 봉인 완료"가
// 새로고침 없이 흘러간다. 연결 직후 지금 단계, 이후 바뀌는 단계, 볼 수 있는 사람 제한을 본다.

package com.gakdo.trace.report.progress;

import static com.gakdo.trace.report.support.TestJwts.CHECKPOINT;
import static com.gakdo.trace.report.support.TestJwts.OTHER_RENTAL;
import static com.gakdo.trace.report.support.TestJwts.RENTAL;
import static com.gakdo.trace.report.support.TestJwts.admin;
import static com.gakdo.trace.report.support.TestJwts.booth;
import static com.gakdo.trace.report.support.TestJwts.capture;
import static com.gakdo.trace.report.support.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.gakdo.trace.report.progress.api.ProgressController;
import com.gakdo.trace.report.progress.application.ProgressStreams;
import com.gakdo.trace.report.progress.domain.ProgressStage;
import com.gakdo.trace.report.progress.domain.ProgressUpdate;
import com.gakdo.trace.report.record.domain.CheckpointView;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.infra.CheckpointViewRepository;
import com.gakdo.trace.report.support.WebTestBase;

@WebMvcTest(controllers = ProgressController.class)
@Import(ProgressStreams.class)
class ProgressApiTest extends WebTestBase {

    private static final String OTHER_CP = "aJ3bYet82WYF8iVlHstT2";
    private static final String SET = "Z4U03wInx137yk13jDsmq";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-12T04:20:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProgressStreams streams;

    @MockitoBean
    private CheckpointViewRepository views;

    private MvcResult open(org.springframework.test.web.servlet.request.RequestPostProcessor who) throws Exception {
        return mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .with(who)).andExpect(request().asyncStarted()).andReturn();
    }

    // 도우미: 한 렌탈에 시점 두 개를 준비한다. 하나는 봉인 끝(등급 B), 하나는 사진만 올라온 상태.
    private void twoCheckpoints() {
        CheckpointView sealed = new CheckpointView(RENTAL, OTHER_CP);
        sealed.recordGrade(SET, Grade.B, "grade-rules-v1", List.of(), List.of(), List.of(), AT);
        sealed.markSealed(AT);
        CheckpointView uploaded = new CheckpointView(RENTAL, CHECKPOINT);
        uploaded.recordUpload(SET, AT, List.of());
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenReturn(List.of(sealed, uploaded));
    }

    // [상황] 고객이 자기 렌탈의 진행률 화면을 연다.
    // [기대] 열자마자 봉인된 시점은 "완료(등급 B)", 사진만 올린 시점은 "업로드 20%"로 받는다.
    @Test
    @DisplayName("붙자마자 시점마다 지금 단계를 받는다: 봉인된 시점은 done(등급), 올리기만 한 시점은 UPLOADED 20%")
    void snapshotOnConnect() throws Exception {
        twoCheckpoints();
        String body = open(customer(RENTAL)).getResponse().getContentAsString();
        assertThat(body).contains("event:done\ndata:{\"checkpointId\":\"" + OTHER_CP + "\",\"grade\":\"B\"}")
                .contains("event:progress\ndata:{\"checkpointId\":\"" + CHECKPOINT
                        + "\",\"stage\":\"UPLOADED\",\"percent\":20,\"at\":\"2026-11-12T04:20:00Z\"}");
    }

    // [상황] 관리자가 진행률 화면을 열어 둔 사이 다른 렌탈과 이 렌탈의 단계가 바뀐다.
    // [기대] 이 렌탈의 "검사 60%"만 바로 받고, 다른 렌탈 소식은 섞이지 않는다.
    @Test
    @DisplayName("새로고침 없이: 붙어 있는 동안 단계가 바뀌면 그 렌탈의 연결에만 바로 흘러간다")
    void liveUpdates() throws Exception {
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenReturn(List.of());
        MvcResult result = open(admin());
        streams.onUpdate(new ProgressUpdate(OTHER_RENTAL, OTHER_CP, ProgressStage.GRADED, AT, null));
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.INSPECTED, AT, null));
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"stage\":\"INSPECTED\",\"percent\":60").doesNotContain(OTHER_CP);
    }

    // [상황] QR을 찍은 폰(촬영 JWT, 시점 하나 전용)으로 진행률을 연다. 같은 렌탈의 다른 시점도 바뀐다.
    // [기대] 자기 시점의 업로드·완료(등급 A)만 보이고 다른 시점은 보이지 않는다.
    @Test
    @DisplayName("촬영 JWT는 자기 시점의 진행률만 받고, 같은 렌탈의 다른 시점은 보이지 않는다")
    void captureTokenSeesOwnCheckpointOnly() throws Exception {
        twoCheckpoints();
        MvcResult result = open(capture());
        streams.onUpdate(new ProgressUpdate(RENTAL, OTHER_CP, ProgressStage.GRADED, AT, null));
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.SEALED, AT, Grade.A));
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(OTHER_CP).contains("\"stage\":\"UPLOADED\"")
                .contains("event:done\ndata:{\"checkpointId\":\"" + CHECKPOINT + "\",\"grade\":\"A\"}");
    }

    // [상황] 남의 렌탈 진행률을 고객·촬영 JWT로 열고, 부스 토큰과 로그인 없이도 열어 본다.
    // [기대] 남의 렌탈은 403(OUT_OF_SCOPE), 부스는 403, 로그인 없으면 401.
    @Test
    @DisplayName("다른 사람 렌탈은 403 OUT_OF_SCOPE, 다른 렌탈의 촬영 JWT도 403, 부스는 403, 토큰 없으면 401")
    void scopeIsChecked() throws Exception {
        String url = "/api/v1/reports/rentals/" + OTHER_RENTAL + "/stream";
        mvc.perform(get(url).with(customer(RENTAL))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        mvc.perform(get(url).with(capture())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream").with(booth())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream")).andExpect(status().isUnauthorized());
    }

    // [상황] "봉인 완료"를 보낸 뒤 늦게 도착한 "등급 80%" 알림과 중복 완료 알림이 온다.
    // [기대] 화면이 뒤로 가지 않게 늦은 알림은 보내지 않고, 완료는 한 번만 보낸다.
    @Test
    @DisplayName("이미 보낸 단계보다 앞선 알림이 늦게 와도 보내지 않는다: done 뒤에 GRADED 80%가 다시 보이지 않는다")
    void staleUpdatesAreDropped() throws Exception {
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenReturn(List.of());
        MvcResult result = open(admin());
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.SEALED, AT, Grade.B));
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.GRADED, AT, null));
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.SEALED, AT, Grade.B));
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("GRADED");
        assertThat(body.split("event:done", -1)).hasSize(2);
    }

    // [상황] 진행률 화면을 여는 순간 DB가 끊겨 지금 단계를 읽지 못한다.
    // [기대] 5xx 오류로 끝나고, 실패한 연결이 알림 받을 대상으로 남아 쌓이지 않는다.
    @Test
    @DisplayName("처음 붙을 때 지금 단계를 읽지 못하면 오류로 끝나고, 그 연결은 구독자로 남지 않는다")
    void failedSnapshotLeavesNoSubscriber() throws Exception {
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenThrow(new IllegalStateException("DB 끊김"));
        int before = streams.subscribers(RENTAL);
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .with(customer(RENTAL))).andExpect(status().is5xxServerError());
        assertThat(streams.subscribers(RENTAL)).isEqualTo(before);
    }
}
