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

    private void twoCheckpoints() {
        CheckpointView sealed = new CheckpointView(RENTAL, OTHER_CP);
        sealed.recordGrade(SET, Grade.B, "grade-rules-v1", List.of(), List.of(), List.of(), AT);
        sealed.markSealed(AT);
        CheckpointView uploaded = new CheckpointView(RENTAL, CHECKPOINT);
        uploaded.recordUpload(SET, AT, List.of());
        when(views.findByRentalIdOrderByIdAsc(RENTAL)).thenReturn(List.of(sealed, uploaded));
    }

    @Test
    @DisplayName("붙자마자 시점마다 지금 단계를 받는다: 봉인된 시점은 done(등급), 올리기만 한 시점은 UPLOADED 20%")
    void snapshotOnConnect() throws Exception {
        twoCheckpoints();
        String body = open(customer(RENTAL)).getResponse().getContentAsString();
        assertThat(body).contains("event:done\ndata:{\"checkpointId\":\"" + OTHER_CP + "\",\"grade\":\"B\"}");
        assertThat(body).contains("event:progress\ndata:{\"checkpointId\":\"" + CHECKPOINT
                + "\",\"stage\":\"UPLOADED\",\"percent\":20,\"at\":\"2026-11-12T04:20:00Z\"}");
    }

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
}
