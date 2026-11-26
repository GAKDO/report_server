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

// (도구) @WebMvcTest = 서버 전체가 아니라 지정한 컨트롤러만 띄워 빠르게 시험한다
@WebMvcTest(controllers = ProgressController.class)
// 연결(SSE) 목록을 관리하는 진짜 객체를 함께 올린다
@Import(ProgressStreams.class)
// WebTestBase를 이어받아 운영과 같은 보안 규칙으로 시험한다
class ProgressApiTest extends WebTestBase {

    // 같은 렌탈의 다른 시점 번호, 촬영 세트 번호, 시각
    private static final String OTHER_CP = "aJ3bYet82WYF8iVlHstT2";
    private static final String SET = "Z4U03wInx137yk13jDsmq";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-12T04:20:00Z");

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) MockMvc = 서버를 실제로 띄우지 않고 HTTP 요청을 흉내 내 컨트롤러를 부르는 도구
    @Autowired
    private MockMvc mvc;

    // 진행률 변화를 직접 흘려보내려고 연결 관리 객체를 받는다
    @Autowired
    private ProgressStreams streams;

    // (도구) @MockitoBean = 스프링 안의 진짜 객체를 가짜(mock)로 바꿔 끼운다. mock은 정해 준 대로만 답하는 대역이다
    // 시점 저장소를 가짜로 바꿔 DB 없이 시점 상태를 정한다
    @MockitoBean
    private CheckpointViewRepository views;

    // 테스트 도구: 주어진 사용자로 진행률 연결(SSE)을 연다
    private MvcResult open(org.springframework.test.web.servlet.request.RequestPostProcessor who) throws Exception {
        // (도구) mvc.perform(...) = 만든 요청을 컨트롤러에 보내고, 결과를 확인할 수 있는 객체를 돌려준다
        // 이벤트 스트림 형식으로 진행률 주소를 부르고, 응답이 열린 채 비동기로 시작됐는지 확인한 뒤 결과를 돌려준다
        return mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream").accept(MediaType.TEXT_EVENT_STREAM)
                .with(who)).andExpect(request().asyncStarted()).andReturn();
    }

    // 도우미: 한 렌탈에 시점 두 개를 준비한다. 하나는 봉인 끝(등급 B), 하나는 사진만 올라온 상태.
    private void twoCheckpoints() {
        // 다른 시점을 만들고
        CheckpointView sealed = new CheckpointView(RENTAL, OTHER_CP);
        // 등급 B를 적용하고
        sealed.recordGrade(SET, Grade.B, "grade-rules-v1", List.of(), List.of(), List.of(), AT);
        // 봉인한다
        sealed.markSealed(AT);
        // 테스트 시점을 만들고
        CheckpointView uploaded = new CheckpointView(RENTAL, CHECKPOINT);
        // 업로드만 적용한다
        uploaded.recordUpload(SET, AT, List.of());
        // (도구) when(...).thenReturn(값) = 그 메서드가 불리면 이 값을 돌려주라고 가짜에게 정해 둔다
        // 렌탈의 시점 목록을 찾으면 두 시점을 돌려주게 한다
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenReturn(List.of(sealed, uploaded));
    }

    // [상황] 고객이 자기 렌탈의 진행률 화면을 연다.
    // [기대] 열자마자 봉인된 시점은 "완료(등급 B)", 사진만 올린 시점은 "업로드 20%"로 받는다.
    @Test
    @DisplayName("붙자마자 시점마다 지금 단계를 받는다: 봉인된 시점은 done(등급), 올리기만 한 시점은 UPLOADED 20%")
    void snapshotOnConnect() throws Exception {
        // 준비: 봉인된 시점과 업로드만 된 시점을 준비한다
        twoCheckpoints();
        // 실행: 고객으로 연결을 열고 지금까지 받은 글자를 읽는다
        String body = open(customer(RENTAL)).getResponse().getContentAsString();
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 확인: 봉인된 시점은 "완료(등급 B)"로 왔는지 확인한다
        assertThat(body).contains("event:done\ndata:{\"checkpointId\":\"" + OTHER_CP + "\",\"grade\":\"B\"}")
                // 업로드만 된 시점은 "업로드 20%"로 왔는지 확인한다
                .contains("event:progress\ndata:{\"checkpointId\":\"" + CHECKPOINT
                        + "\",\"stage\":\"UPLOADED\",\"percent\":20,\"at\":\"2026-11-12T04:20:00Z\"}");
    }

    // [상황] 관리자가 진행률 화면을 열어 둔 사이 다른 렌탈과 이 렌탈의 단계가 바뀐다.
    // [기대] 이 렌탈의 "검사 60%"만 바로 받고, 다른 렌탈 소식은 섞이지 않는다.
    @Test
    @DisplayName("새로고침 없이: 붙어 있는 동안 단계가 바뀌면 그 렌탈의 연결에만 바로 흘러간다")
    void liveUpdates() throws Exception {
        // 준비: 처음에는 시점이 없게 한다
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenReturn(List.of());
        // 관리자로 연결을 열어 둔다
        MvcResult result = open(admin());
        // 실행: 다른 렌탈의 "등급" 소식을 흘려보낸다
        streams.onUpdate(new ProgressUpdate(OTHER_RENTAL, OTHER_CP, ProgressStage.GRADED, AT, null));
        // 이 렌탈의 "검사" 소식을 흘려보낸다
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.INSPECTED, AT, null));
        // 확인: 연결로 받은 글자를 읽는다
        String body = result.getResponse().getContentAsString();
        // 이 렌탈의 "검사 60%"는 있고 다른 렌탈 소식은 없는지 확인한다
        assertThat(body).contains("\"stage\":\"INSPECTED\",\"percent\":60").doesNotContain(OTHER_CP);
    }

    // [상황] QR을 찍은 폰(촬영 JWT, 시점 하나 전용)으로 진행률을 연다. 같은 렌탈의 다른 시점도 바뀐다.
    // [기대] 자기 시점의 업로드·완료(등급 A)만 보이고 다른 시점은 보이지 않는다.
    @Test
    @DisplayName("촬영 JWT는 자기 시점의 진행률만 받고, 같은 렌탈의 다른 시점은 보이지 않는다")
    void captureTokenSeesOwnCheckpointOnly() throws Exception {
        // 준비: 시점 두 개를 준비한다
        twoCheckpoints();
        // 촬영 JWT로 연결을 연다
        MvcResult result = open(capture());
        // 실행: 같은 렌탈의 다른 시점 소식을 흘려보낸다
        streams.onUpdate(new ProgressUpdate(RENTAL, OTHER_CP, ProgressStage.GRADED, AT, null));
        // 자기 시점의 "봉인(등급 A)" 소식을 흘려보낸다
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.SEALED, AT, Grade.A));
        // 확인: 받은 글자를 읽는다
        String body = result.getResponse().getContentAsString();
        // 다른 시점은 없고 자기 시점의 업로드 단계는 있는지 확인한다
        assertThat(body).doesNotContain(OTHER_CP).contains("\"stage\":\"UPLOADED\"")
                // 자기 시점의 완료(등급 A)도 받았는지 확인한다
                .contains("event:done\ndata:{\"checkpointId\":\"" + CHECKPOINT + "\",\"grade\":\"A\"}");
    }

    // [상황] 남의 렌탈 진행률을 고객·촬영 JWT로 열고, 부스 토큰과 로그인 없이도 열어 본다.
    // [기대] 남의 렌탈은 403(OUT_OF_SCOPE), 부스는 403, 로그인 없으면 401.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 401 = 로그인 필요(토큰이 없거나 만료·위조됨)
    @Test
    @DisplayName("다른 사람 렌탈은 403 OUT_OF_SCOPE, 다른 렌탈의 촬영 JWT도 403, 부스는 403, 토큰 없으면 401")
    void scopeIsChecked() throws Exception {
        // 준비: 남의 렌탈 진행률 주소를 만든다
        String url = "/api/v1/reports/rentals/" + OTHER_RENTAL + "/stream";
        // (도구) andExpect(...) = 응답이 기대와 같은지 확인하고, 다르면 테스트를 실패시킨다
        // 실행+확인: 고객이 남의 렌탈을 열면 403인지 확인한다
        mvc.perform(get(url).with(customer(RENTAL))).andExpect(status().isForbidden())
                // (도구) jsonPath("$.code") = 응답 JSON에서 code 칸을 가리킨다. value(...)로 그 값이 맞는지 본다
                // 오류 코드가 "범위 밖"인지 확인한다
                .andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 다른 렌탈의 촬영 JWT로 열어도 403인지 확인한다
        mvc.perform(get(url).with(capture())).andExpect(status().isForbidden());
        // 부스 토큰으로 열면 403인지 확인한다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream").with(booth())).andExpect(status().isForbidden());
        // 토큰 없이 열면 401인지 확인한다
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream")).andExpect(status().isUnauthorized());
    }

    // [상황] "봉인 완료"를 보낸 뒤 늦게 도착한 "등급 80%" 알림과 중복 완료 알림이 온다.
    // [기대] 화면이 뒤로 가지 않게 늦은 알림은 보내지 않고, 완료는 한 번만 보낸다.
    @Test
    @DisplayName("이미 보낸 단계보다 앞선 알림이 늦게 와도 보내지 않는다: done 뒤에 GRADED 80%가 다시 보이지 않는다")
    void staleUpdatesAreDropped() throws Exception {
        // 준비: 처음에는 시점이 없게 한다
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenReturn(List.of());
        // 관리자로 연결을 열어 둔다
        MvcResult result = open(admin());
        // 실행: "봉인 완료" 소식을 먼저 흘려보낸다
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.SEALED, AT, Grade.B));
        // 늦게 온 "등급" 소식을 흘려보낸다
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.GRADED, AT, null));
        // 같은 "봉인 완료" 소식을 한 번 더 흘려보낸다
        streams.onUpdate(new ProgressUpdate(RENTAL, CHECKPOINT, ProgressStage.SEALED, AT, Grade.B));
        // 확인: 받은 글자를 읽는다
        String body = result.getResponse().getContentAsString();
        // 늦은 "등급" 소식이 보내지지 않았는지 확인한다
        assertThat(body).doesNotContain("GRADED");
        // "event:done"으로 잘랐을 때 조각이 2개 = 완료가 딱 한 번 나갔는지 확인한다
        assertThat(body.split("event:done", -1)).hasSize(2);
    }

    // [상황] 진행률 화면을 여는 순간 DB가 끊겨 지금 단계를 읽지 못한다.
    // [기대] 5xx 오류로 끝나고, 실패한 연결이 알림 받을 대상으로 남아 쌓이지 않는다.
    @Test
    @DisplayName("처음 붙을 때 지금 단계를 읽지 못하면 오류로 끝나고, 그 연결은 구독자로 남지 않는다")
    void failedSnapshotLeavesNoSubscriber() throws Exception {
        // (도구) thenThrow(오류) = 그 메서드가 불리면 이 오류를 던지라고 가짜에게 정해 둔다
        // 준비: 시점 목록을 읽으면 "DB 끊김" 오류가 나게 한다
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenThrow(new IllegalStateException("DB 끊김"));
        // 지금 연결 수를 적어 둔다
        int before = streams.subscribers(RENTAL);
        // 실행: 고객으로 연결을 열고
        mvc.perform(get("/api/v1/reports/rentals/" + RENTAL + "/stream").accept(MediaType.TEXT_EVENT_STREAM)
                // 확인: 서버 오류(5xx)로 끝나는지 확인한다
                .with(customer(RENTAL))).andExpect(status().is5xxServerError());
        // 연결 수가 그대로인지 확인한다(실패한 연결이 남아 쌓이지 않게)
        assertThat(streams.subscribers(RENTAL)).isEqualTo(before);
    }
}
