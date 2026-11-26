// 이 파일이 확인하는 것: report-server(렌탈 기록·봉인·검증 서버)의 처음부터 끝까지 흐름 한 번.
// TRACE는 출고(OUT)·수령(RCV)·반납(RET) 시점마다 촬영·검사가 끝나면 그 결과를 "봉인"한다.
// 봉인 = 등급·사진 지문(SHA-256)을 묶어 서명하고, 앞 시점 기록의 해시에 이어 붙이는 것(해시 체인).
// 그래서 나중에 사진 한 장이나 등급 하나만 몰래 바꿔도 "검증 실패"로 드러난다.
// 진짜 DB(PostgreSQL)·메시지 큐(RabbitMQ)·사진 저장소(S3 호환)를 Docker로 띄워 시험하므로,
// Docker가 없는 곳(Claude 세션)에서는 건너뛰고 GitHub Actions에서만 돈다.

package com.gakdo.trace.report;

import static com.gakdo.trace.report.support.TestJwts.admin;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.report.common.infra.PublicIds;
import com.gakdo.trace.report.common.infra.Times;
import com.gakdo.trace.report.record.application.SealService;
import com.gakdo.trace.report.support.TestKeys;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 실제 PostgreSQL(Flyway V1·V2)·RabbitMQ·S3 호환 저장소로 이벤트 → 읽기 모델 → 봉인(trace.record.sealed) → 공개 검증 →
 * 사진 바꿔치기 → 검증 실패 → 수동 봉인까지 확인한다. Docker가 없으면 건너뛴다(GitHub Actions에서 돈다).
 */
// (도구) @SpringBootTest = 진짜 서버처럼 스프링 앱 전체를 띄운다. properties로 진단 대기 1시간·검증 캐시 끔·정리 주기 1시간·공개 주소를 테스트용으로 정한다
@SpringBootTest(properties = {"app.chain.diagnostics-wait=PT1H", "app.verify.cache-ttl=PT0S",
        "app.chain.sweep-interval=PT1H", "app.web-public-url=https://gakdo.site"})
// (도구) @AutoConfigureMockMvc = 띄운 앱에 HTTP 요청을 흉내 내 보내는 MockMvc를 준비해 준다
@AutoConfigureMockMvc
// (도구) @Testcontainers = 테스트용 DB·메시지 큐를 Docker 컨테이너로 띄운다. Docker가 없으면 이 클래스를 건너뛴다
@Testcontainers(disabledWithoutDocker = true)
class ReportIntegrationTest {

    // 사진을 올릴 저장소 버킷 이름
    private static final String BUCKET = "trace-captures";

    // (도구) @Container = 이 컨테이너를 테스트 시작 전에 띄우고 끝나면 내린다. @ServiceConnection = 컨테이너 주소를 스프링 설정에 자동으로 넣는다
    @Container
    @ServiceConnection
    // PostgreSQL 16을 trace라는 DB 이름으로 띄운다
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("trace");

    @Container
    @ServiceConnection
    // RabbitMQ 3.13을 띄운다
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    /** MinIO 대신 moto(S3 호환, mirror.gcr.io). capture-server CI와 같은 이미지 */
    // S3 호환 저장소(moto)를 띄울 준비를 한다
    static final GenericContainer<?> S3 = new GenericContainer<>("mirror.gcr.io/motoserver/moto:5.1.16")
            // 컨테이너의 5000번 포트를 밖으로 연다
            .withExposedPorts(5000)
            // "/"가 200으로 답할 때까지 기다린다(준비가 끝난 뒤 쓰려고)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    // (도구) @DynamicPropertySource = 앱이 뜨기 전에 설정 값을 코드로 넣는다(컨테이너 주소처럼 실행해 봐야 아는 값)
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        // 저장소 컨테이너를 직접 띄운다(주소를 알아야 설정에 넣을 수 있어서)
        S3.start();
        // 저장소 주소를 띄운 컨테이너의 실제 주소·포트로 넣는다
        registry.add("app.storage.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(5000));
        // 테스트 저장소 접속 이름(아무 값이나 받는 가짜 저장소라 test)
        registry.add("app.storage.access-key", () -> "test");
        // 테스트 저장소 접속 비밀(역시 test)
        registry.add("app.storage.secret-key", () -> "test");
        // 체인 서명 열쇠를 실행할 때 만든 테스트 열쇠로 넣는다
        registry.add("app.chain.signing-key", TestKeys::ed25519PemBase64);
    }

    // (도구) @Autowired = 스프링이 만들어 둔 객체를 이 칸에 넣어 준다
    // (도구) MockMvc = HTTP 요청을 흉내 내 서버 API를 부르는 도구
    @Autowired
    private MockMvc mvc;
    // 응답 JSON을 읽는 변환기
    @Autowired
    private ObjectMapper json;
    // 저장소에 버킷을 만들고 사진을 올리는 S3 도구
    @Autowired
    private S3Client s3;
    // 메시지 큐로 이벤트를 보내고 받는 도구
    @Autowired
    private RabbitTemplate rabbit;
    // 테스트용 큐를 만들고 연결하는 관리 도구
    @Autowired
    private RabbitAdmin rabbitAdmin;
    // 진단 대기 뒤 봉인을 직접 불러 보려고 봉인 서비스를 받는다
    @Autowired
    private SealService seals;

    // 사진 바이트를 만들 난수 도구. 씨앗(11)을 고정해 실행마다 같은 값이 나오게 한다
    private final Random random = new Random(11);

    // [상황] 렌탈 하나에 대해 아래 순서를 차례로 진행한다(각 단계는 아래 도우미 메서드).
    //   1) 출고: 시점 시작·진단·사진 업로드·등급 이벤트가 오면 바로 봉인되고 타임라인에 보인다.
    //   2) 수령: 진단 없이 등급만 오면 잠시 기다렸다가 봉인되고, 출고 기록에 이어진다.
    //   3) 공개 검증 페이지는 등급·지문만 보여 주고 렌탈 번호·사진 ID는 숨긴다.
    //   4) 저장소의 수령 사진 한 장을 바꾸면 검증이 실패하고, 되돌리면 다시 통과한다.
    //   5) 반납: 관리자가 직접 봉인(수동 봉인)하면 기록이 남고, 두 번 봉인은 막힌다.
    //   6) 순서가 틀린 봉인·모르는 렌탈은 거절한다. 7) 시험용(TEST-) 렌탈은 통째로 지울 수 있다.
    // [기대] 모든 단계가 위 설명대로 동작한다.
    @Test
    @DisplayName("이벤트로 시점을 봉인해 체인을 잇고, 공개 검증은 등급·지문만 보이며, 사진 한 장을 바꾸면 검증이 실패하고, 수동 봉인은 기록이 남는다")
    void endToEnd() throws Exception {
        // 준비: 저장소에 사진 버킷을 만든다
        s3.createBucket(b -> b.bucket(BUCKET));
        // "봉인됨" 이벤트를 받아 볼 테스트용 큐를 만든다
        Queue sealedQueue = new Queue("test.record.sealed", false, false, false);
        // 큐를 RabbitMQ에 등록한다
        rabbitAdmin.declareQueue(sealedQueue);
        // trace.events 교환소의 trace.record.sealed 이벤트가 이 큐로 오게 잇는다
        rabbitAdmin.declareBinding(BindingBuilder.bind(sealedQueue).to(new TopicExchange("trace.events"))
                .with("trace.record.sealed"));

        // 새 렌탈 번호를 만든다
        String rental = PublicIds.next();
        // 출고 시점 번호
        String out = PublicIds.next();
        // 수령 시점 번호
        String rcv = PublicIds.next();
        // 반납 시점 번호
        String ret = PublicIds.next();

        // 실행+확인: 1) 출고를 봉인하고 타임라인을 확인한다
        OutSealed sealedOut = sealOutAndCheckTimeline(rental, out);
        // 2) 수령을 기다렸다 봉인한다
        List<String[]> rcvPhotos = sealRcvAfterWaiting(rental, rcv, sealedOut.event());
        // 3) 공개 검증 응답을 확인한다
        checkPublicVerification(sealedOut.verifyPath(), rental, sealedOut.firstPhotoId());
        // 4) 사진을 바꿔 검증이 깨지는지 확인한다
        checkChangedPhotoBreaksChain(rental, sealedOut.verifyPath(), rcvPhotos.get(1));
        // 5) 반납을 수동 봉인한다
        checkManualSeal(rental, ret);
        // 6) 순서 위반·모르는 렌탈을 확인한다
        checkOrderViolationAndUnknownRental();
        // 7) 시험 렌탈 지우기를 확인한다
        checkTestPurge(rental, out);
    }

    /** 시험 렌탈 지우기(trace.checkpoint.purged, 시리얼 TEST-): 렌탈의 체인·시점·리포트가 모두 사라져 타임라인이 404 */
    // 7) 시험 렌탈 지우기: 기기 일련번호가 TEST-로 시작하는 시험 렌탈이면 기록을 모두 지워 타임라인이 404가 된다.
    // ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    private void checkTestPurge(String rental, String out) {
        // 일련번호 TEST-SERIAL-1인 "시험 시점 지움" 이벤트를 보낸다
        send("trace.checkpoint.purged", envelope(rental, out) + ",\"checkpointType\":\"OUT\",\"deviceId\":\""
                + PublicIds.next() + "\",\"serial\":\"TEST-SERIAL-1\"");
        // 리스너가 처리할 때까지 기다린다
        await(() -> {
            try {
                // 관리자로 타임라인을 열어 404가 되었는지 본다
                return mvc.perform(get("/api/v1/reports/rentals/" + rental).with(admin())).andReturn().getResponse()
                        .getStatus() == 404;
            } catch (Exception e) {
                // 요청 중 오류가 나면 "아직 아님"으로 보고 다시 기다린다
                return false;
            }
        });
    }

    /** 출고 봉인 결과: 봉인 이벤트, 공개 검증 경로, 첫 사진 ID(공개 응답에 나오면 안 됨) */
    // (도구) record = 값만 담는 작은 데이터 상자
    private record OutSealed(JsonNode event, String verifyPath, String firstPhotoId) {
    }

    /** ── 출고: 시점 시작(기종, 1.1)·진단(배터리 최대 용량, 1.1)이 먼저 오고, 업로드·등급(핀, 1.1)이 오면 바로 봉인 ── */
    // 1) 출고 시점: 시점 시작(기종 맥북 에어 M2)·진단(배터리 최대 용량 91.2%)·사진 2장·등급 A(흠집 핀 1개)
    // 이벤트를 보내면 체인 1번 기록으로 봉인된다. 같은 이벤트가 또 와도 두 번 봉인하지 않는다.
    // 타임라인에는 봉인됨·기종·핀 좌표·배터리 용량·공개 검증 주소(gakdo.site/verify/...)가 보인다.
    private OutSealed sealOutAndCheckTimeline(String rental, String out) throws Exception {
        // 준비: 출고 시점 시작 이벤트(기기·TEST 일련번호·기종)를 보낸다
        send("trace.checkpoint.started", envelope11(rental, out) + ",\"checkpointType\":\"OUT\",\"deviceId\":\""
                + PublicIds.next() + "\",\"serial\":\"TEST-SERIAL-1\",\"modelCode\":\"MBA13-M2\""
                + ",\"modelName\":\"MacBook Air (M2, 2022)\"");
        // 진단 이벤트(배터리 최대 용량 91.2%)를 보낸다
        send("trace.diagnostics.recorded", envelope11(rental, out) + ",\"runId\":\"" + PublicIds.next() + "\",\"sha256\":\""
                + sha("diag-out") + "\",\"baseRunId\":null,\"flags\":[],\"maxCapacityPct\":91.2");
        // 사진 2장을 올리고 업로드 이벤트를 보낸다
        List<String[]> outPhotos = uploadPhotos(rental, out, 2);
        // 흠집 핀 ID를 만든다
        String pinId = PublicIds.next();
        // 등급 A·흠집 핀 1개가 든 등급 이벤트 본문을 만든다
        String gradedOut = envelope11(rental, out) + gradedBody("OUT", "A", outPhotos)
                + ",\"pins\":[{\"defectId\":\"" + pinId + "\",\"face\":\"TOP\",\"x\":812.5,\"y\":403.0,\"isNew\":true}]";
        // 실행: 등급 이벤트를 보낸다
        send("trace.checkpoint.graded", gradedOut);
        // 확인: "봉인됨" 이벤트를 받는다
        JsonNode sealed1 = receiveSealed();
        // (도구) assertThat(값) = "이 값이 ~여야 한다"를 확인하는 도구. 뒤의 조건이 틀리면 테스트가 실패한다
        // 체인 1번 기록인지 확인한다
        assertThat(sealed1.path("seq").asInt()).isEqualTo(1);
        // 출고 시점인지 확인한다
        assertThat(sealed1.path("checkpointType").asText()).isEqualTo("OUT");
        // 첫 기록이라 앞 기록 해시가 null인지 확인한다
        assertThat(sealed1.path("prevHash").isNull()).isTrue();
        // 이벤트 형식 버전이 1.0인지 확인한다
        assertThat(sealed1.path("schemaVersion").asText()).isEqualTo("1.0");

        // 같은 eventId가 다시 와도 두 번 봉인하지 않는다
        // 같은 등급 이벤트를 한 번 더 보낸다
        send("trace.checkpoint.graded", gradedOut);
        // 1.5초 기다려도 "봉인됨"이 또 오지 않는지 확인한다
        assertThat(rabbit.receive("test.record.sealed", 1500)).isNull();

        // 관리자로 타임라인을 읽는다
        JsonNode timeline = getJson("/api/v1/reports/rentals/" + rental);
        // 체인 상태가 VALID인지 확인한다
        assertThat(timeline.path("chainStatus").asText()).isEqualTo("VALID");
        // 첫 시점이 봉인됨인지 확인한다
        assertThat(timeline.path("checkpoints").get(0).path("status").asText()).isEqualTo("SEALED");
        // 기종 코드가 보이는지 확인한다
        assertThat(timeline.path("modelCode").asText()).isEqualTo("MBA13-M2");
        // 출고 시점 칸을 꺼낸다
        JsonNode outView = timeline.path("checkpoints").get(0);
        // 핀이 1개인지 확인한다
        assertThat(outView.path("pins")).hasSize(1);
        // 핀의 흠집 ID가 맞는지 확인한다
        assertThat(outView.path("pins").get(0).path("defectId").asText()).isEqualTo(pinId);
        // 핀의 x 좌표가 맞는지 확인한다
        assertThat(outView.path("pins").get(0).path("x").asDouble()).isEqualTo(812.5);
        // 새 흠집 표시가 맞는지 확인한다
        assertThat(outView.path("pins").get(0).path("isNew").asBoolean()).isTrue();
        // 배터리 최대 용량 91.2%가 보이는지 확인한다
        assertThat(outView.path("diagnosticsSummary").path("maxCapacityPct").asDouble()).isEqualTo(91.2);
        // 공개 검증 주소를 꺼낸다
        String verifyUrl = timeline.path("verifyUrl").asText();
        // gakdo.site/verify/ 뒤에 32자 16진수가 붙은 모양인지 확인한다
        assertThat(verifyUrl).matches("https://gakdo\\.site/verify/[0-9a-f]{32}");
        // 주소 끝의 검증 번호로 공개 검증 API 경로를 만든다
        String verifyPath = "/api/v1/verify/" + verifyUrl.substring(verifyUrl.lastIndexOf('/') + 1);
        // 봉인 이벤트·검증 경로·첫 사진 ID를 묶어 돌려준다
        return new OutSealed(sealed1, verifyPath, outPhotos.get(0)[0]);
    }

    /** ── 수령: 진단 없이 등급만 → 기다렸다가(스케줄러가 부르는 것과 같게) 봉인, 앞 기록 해시에 잇는다 ── */
    // 2) 수령 시점: 진단 없이 등급(B)만 오면 진단을 기다리느라 바로 봉인하지 않는다.
    // 대기 시간이 지나면 봉인되어 체인 2번 기록이 되고, 앞 기록(출고)의 해시에 이어진다.
    private List<String[]> sealRcvAfterWaiting(String rental, String rcv, JsonNode sealed1) throws Exception {
        // 준비: 수령 사진 2장을 올린다
        List<String[]> rcvPhotos = uploadPhotos(rental, rcv, 2);
        // 실행: 진단 없이 등급 B 이벤트만 보낸다
        send("trace.checkpoint.graded", envelope(rental, rcv) + gradedBody("RCV", "B", rcvPhotos));
        // 확인: 1.5초 안에는 봉인되지 않는지 확인한다(진단을 기다리는 중)
        assertThat(rabbit.receive("test.record.sealed", 1500)).isNull();
        // 2시간 뒤 시각으로 봉인을 불러(대기 1시간이 지난 것처럼) 봉인될 때까지 기다린다
        await(() -> seals.sealIfReady(rcv, Times.now().plusHours(2)).isPresent());
        // "봉인됨" 이벤트를 받는다
        JsonNode sealed2 = receiveSealed();
        // 체인 2번 기록인지 확인한다
        assertThat(sealed2.path("seq").asInt()).isEqualTo(2);
        // 체인 전체를 읽는다
        JsonNode chain = getJson("/api/v1/reports/rentals/" + rental + "/chain");
        // 체인이 온전한지 확인한다
        assertThat(chain.path("valid").asBoolean()).isTrue();
        // 기록이 2개인지 확인한다
        assertThat(chain.path("records")).hasSize(2);
        // 앞 기록 해시가 비어 있지 않고, 봉인 내용 해시와는 다른 값(기록 해시)인지 확인한다
        assertThat(sealed2.path("prevHash").asText()).isNotBlank().isNotEqualTo(sealed1.path("payloadHash").asText());
        // 수령 사진 정보를 돌려준다(4번 단계에서 씀)
        return rcvPhotos;
    }

    /** 공개 검증: 등급·시점·지문·서명 시각만 */
    // 3) 공개 검증(로그인 없이 누구나 QR로 보는 페이지): 체인이 온전하고 기종 이름·시점별 등급이 보인다.
    // 서명 값, 렌탈 번호, 사진 ID 같은 내부 정보는 응답 어디에도 없다.
    private void checkPublicVerification(String verifyPath, String rental, String outPhotoId) throws Exception {
        // (도구) mvc.perform(...).andExpect(...) = 요청을 보내고 응답이 기대와 같은지 확인한다
        // 실행: 로그인 없이 공개 검증을 부르고 200인지 확인한 뒤 본문 JSON을 읽는다
        JsonNode verify = json.readTree(mvc.perform(get(verifyPath)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        // 확인: 체인이 온전하다고 나오는지 확인한다
        assertThat(verify.path("valid").asBoolean()).isTrue();
        // 기종 이름이 보이는지 확인한다
        assertThat(verify.path("modelName").asText()).isEqualTo("MacBook Air (M2, 2022)");
        // 두 번째 기록 등급이 B인지 확인한다
        assertThat(verify.path("records").get(1).path("grade").asText()).isEqualTo("B");
        // 서명 칸이 없는지 확인한다
        assertThat(verify.path("records").get(0).has("signature")).isFalse();
        // 응답 어디에도 렌탈 번호·사진 ID가 없는지 확인한다
        assertThat(verify.toString()).doesNotContain(rental).doesNotContain(outPhotoId);
    }

    /** ── 사진 한 장을 바꾸면 검증이 실패한다(되돌리면 다시 맞다) ── */
    // 4) 저장소의 사진 한 장을 다른 파일로 바꾸면 공개 검증이 실패하고 타임라인 체인 상태가 BROKEN이 된다.
    // 원래 사진으로 되돌리면 다시 온전하다고 나온다.
    private void checkChangedPhotoBreaksChain(String rental, String verifyPath, String[] photo) throws Exception {
        // 실행: 저장소의 수령 사진을 "바꾼 사진" 글자 파일로 덮어쓴다
        s3.putObject(b -> b.bucket(BUCKET).key(photo[2]), RequestBody.fromBytes("바꾼 사진".getBytes()));
        // 공개 검증을 다시 부른다
        JsonNode broken = json.readTree(mvc.perform(get(verifyPath)).andReturn().getResponse().getContentAsString());
        // 확인: 검증이 실패하는지 확인한다
        assertThat(broken.path("valid").asBoolean()).isFalse();
        // 타임라인 체인 상태가 BROKEN인지 확인한다
        assertThat(getJson("/api/v1/reports/rentals/" + rental).path("chainStatus").asText()).isEqualTo("BROKEN");
        // 원래 사진 바이트로 되돌린다
        s3.putObject(b -> b.bucket(BUCKET).key(photo[2]), RequestBody.fromBytes(bytesOf(photo)));
        // 다시 온전하다고 나오는지 확인한다
        assertThat(getJson("/api/v1/reports/rentals/" + rental + "/chain").path("valid").asBoolean()).isTrue();
    }

    /** ── 반납: 파이프라인 없이 관리자 수동 봉인(사용 기록) ── */
    // 5) 반납 시점 수동 봉인: 집 PC가 꺼져 자동 검사를 못 한 경우 관리자가 사유를 적고 직접 봉인한다.
    // 체인 3번 기록이 되고, 같은 시점을 또 봉인하면 409. 수동 봉인 사용 기록에 누가 했는지 남는다.
    // ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    private void checkManualSeal(String rental, String ret) throws Exception {
        // 준비: 반납 시점 수동 봉인 주소를 만든다
        String sealUrl = "/api/v1/reports/rentals/" + rental + "/checkpoints/" + ret + "/seal";
        // 요청 본문(반납·등급 C·사유)을 만든다
        String body = "{\"checkpointType\":\"RET\",\"grade\":\"C\",\"reason\":\"집 PC 꺼짐, 육안 확인 후 봉인\"}";
        // 실행: 관리자가 수동 봉인을 보낸다
        mvc.perform(post(sealUrl).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                // 확인: 201(새로 만듦)인지 확인한다
                .andExpect(status().isCreated());
        // "봉인됨" 이벤트가 3번 기록인지 확인한다
        assertThat(receiveSealed().path("seq").asInt()).isEqualTo(3);
        // 같은 시점을 또 봉인하면
        mvc.perform(post(sealUrl).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                // 409인지 확인한다
                .andExpect(status().isConflict());
        // 수동 봉인 사용 기록을 읽는다
        JsonNode log = getJson("/api/v1/reports/manual-seals");
        // 첫 기록이 반납 시점인지 확인한다
        assertThat(log.path("items").get(0).path("checkpointId").asText()).isEqualTo(ret);
        // 봉인한 사람이 테스트 관리자 ID인지 확인한다
        assertThat(log.path("items").get(0).path("sealedBy").asText()).isEqualTo("admin0000000000000001");
        // 체인이 여전히 온전한지 확인한다
        assertThat(getJson("/api/v1/reports/rentals/" + rental + "/chain").path("valid").asBoolean()).isTrue();
    }

    /** 앞 시점(출고)이 없는 렌탈의 수령을 수동 봉인하면 409, 모르는 렌탈은 404 */
    // 6) 출고 봉인이 없는 렌탈의 수령을 먼저 봉인하려 하면 409(순서 위반), 없는 렌탈 조회는 404.
    // ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 404 = 찾을 수 없음(없는 주소나 데이터)
    private void checkOrderViolationAndUnknownRental() throws Exception {
        // 준비: 출고 봉인이 없는 새 렌탈 번호를 만든다
        String other = PublicIds.next();
        // 실행+확인: 그 렌탈의 수령을 먼저 수동 봉인하려 한다
        mvc.perform(post("/api/v1/reports/rentals/" + other + "/checkpoints/" + PublicIds.next() + "/seal").with(admin())
                // 본문이 JSON이라고 알린다
                .contentType(MediaType.APPLICATION_JSON)
                // 수령·등급 A·사유 본문을 싣는다
                .content("{\"checkpointType\":\"RCV\",\"grade\":\"A\",\"reason\":\"순서 위반 시험\"}"))
                // 409(순서 위반)인지 확인한다
                .andExpect(status().isConflict());
        // 없는 렌탈 타임라인은 404인지 확인한다
        mvc.perform(get("/api/v1/reports/rentals/" + PublicIds.next()).with(admin())).andExpect(status().isNotFound());
    }

    // ── 도우미 ──

    // 올린 사진 바이트를 사진 ID별로 기억해 둔다(바꾼 사진을 되돌릴 때 쓰려고)
    private final java.util.Map<String, byte[]> files = new java.util.HashMap<>();

    /** 사진을 저장소에 올리고 trace.capture.uploaded를 보낸다. [photoId, sha256, objectKey] */
    // 도우미: 사진(임의 바이트)을 저장소에 올리고 "사진 업로드됨" 이벤트를 보낸다.
    private List<String[]> uploadPhotos(String rental, String checkpoint, int count) throws Exception {
        // 촬영 세트 번호를 만든다
        String setId = PublicIds.next();
        // 사진 정보를 모을 목록
        List<String[]> photos = new ArrayList<>();
        // 이벤트에 넣을 사진 목록 JSON을 이어 붙일 상자
        StringBuilder items = new StringBuilder();
        // 첫 장은 윗면, 둘째 장은 아랫면
        String[] faces = {"TOP", "BOT"};
        // 사진 수만큼
        for (int i = 0; i < count; i++) {
            // 1~3KB 크기의 바이트 배열을 만든다
            byte[] bytes = new byte[1024 + random.nextInt(2048)];
            // 난수로 채워 가짜 사진을 만든다
            random.nextBytes(bytes);
            // 사진 ID를 만든다
            String id = PublicIds.next();
            // 저장소 경로를 정한다
            String key = "captures/" + setId + "/" + faces[i] + "-L1-CAM1.jpg";
            // 저장소에 올린다
            s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(bytes));
            // 원래 바이트를 기억해 둔다
            files.put(id, bytes);
            // 사진의 SHA-256 지문을 구한다
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            // [사진 ID, 지문, 경로, 세트 번호]를 목록에 넣는다
            photos.add(new String[] {id, sha, key, setId});
            // 사진 하나의 JSON(ID·면·조명·카메라·경로·지문)을 이어 붙인다
            items.append(i > 0 ? "," : "").append("{\"photoId\":\"").append(id).append("\",\"face\":\"").append(faces[i])
                    .append("\",\"light\":\"L1\",\"camera\":\"CAM1\",\"objectKey\":\"").append(key)
                    .append("\",\"sha256\":\"").append(sha).append("\"}");
        }
        // "사진 업로드됨" 이벤트를 보낸다
        send("trace.capture.uploaded", envelope(rental, checkpoint) + ",\"captureSetId\":\"" + setId
                + "\",\"source\":\"BOOTH\",\"needDetection\":false,\"photos\":[" + items + "]");
        // 사진 정보 목록을 돌려준다
        return photos;
    }

    // 테스트 도구: 기억해 둔 원래 사진 바이트를 꺼낸다
    private byte[] bytesOf(String[] photo) {
        // 사진 ID로 찾아 돌려준다
        return files.get(photo[0]);
    }

    // 테스트 도구: 등급 이벤트 본문(봉투 뒤에 붙는 부분)을 만든다
    private static String gradedBody(String type, String grade, List<String[]> photos) {
        // 사진 목록 JSON을 이어 붙일 상자
        StringBuilder items = new StringBuilder();
        // 사진마다
        for (int i = 0; i < photos.size(); i++) {
            // 사진 ID와 지문을 JSON으로 이어 붙인다
            items.append(i > 0 ? "," : "").append("{\"photoId\":\"").append(photos.get(i)[0]).append("\",\"sha256\":\"")
                    .append(photos.get(i)[1]).append("\"}");
        }
        // 세트·시점 종류·등급·규칙 버전·판정 못 한 면(SCR1)·사진 목록을 이어 돌려준다
        return ",\"captureSetId\":\"" + photos.get(0)[3] + "\",\"checkpointType\":\"" + type + "\",\"grade\":\"" + grade
                + "\",\"ruleVersion\":\"grade-rule-1.0\",\"newDefectIds\":[],\"unjudgeableFaces\":[\"SCR1\"],\"photos\":["
                + items + "]";
    }

    // 도우미: 모든 이벤트에 공통으로 붙는 봉투(이벤트 번호·발생 시각·렌탈·시점).
    private static String envelope(String rental, String checkpoint) {
        // 1.0 형식 봉투 JSON 앞부분을 만든다(닫는 괄호는 send가 붙임)
        return "{\"schemaVersion\":\"1.0\",\"eventId\":\"" + PublicIds.next() + "\",\"occurredAt\":\"2026-11-12T09:00:00+09:00\""
                + ",\"rentalId\":\"" + rental + "\",\"checkpointId\":\"" + checkpoint + "\"";
    }

    // 테스트 도구: 1.1 형식 봉투를 만든다
    private static String envelope11(String rental, String checkpoint) {
        // 1.0 봉투에서 버전 글자만 1.1로 바꾼다
        return envelope(rental, checkpoint).replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"1.1\"");
    }

    // 테스트 도구: 이벤트 본문을 닫아 메시지 큐로 보낸다
    private void send(String routingKey, String partial) {
        // 본문 끝에 }를 붙이고 JSON 형식으로 메시지를 만든다
        Message message = MessageBuilder.withBody((partial + "}").getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        // trace.events 교환소에 그 경로 이름으로 보낸다
        rabbit.send("trace.events", routingKey, message);
    }

    // 도우미: "봉인됨" 이벤트가 나올 때까지 최대 15초 기다려 받는다.
    private JsonNode receiveSealed() throws Exception {
        // 테스트 큐에서 최대 15초 기다려 메시지를 받는다
        Message m = rabbit.receive("test.record.sealed", 15_000);
        // 받았는지 확인한다(as = 실패 때 보일 이름)
        assertThat(m).as("trace.record.sealed").isNotNull();
        // 본문을 JSON으로 읽어 돌려준다
        return json.readTree(m.getBody());
    }

    // 테스트 도구: 관리자로 GET을 보내 200인지 확인하고 본문 JSON을 돌려준다
    private JsonNode getJson(String url) throws Exception {
        // 요청을 보내고 200을 확인한 뒤 본문 글자를 UTF-8로 읽어 JSON으로 바꾼다
        return json.readTree(mvc.perform(get(url).with(admin())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    // 테스트 도구: 글자의 SHA-256 지문을 16진수로 만든다
    private static String sha(String text) throws Exception {
        // 글자를 UTF-8 바이트로 바꿔 해시를 구하고 16진수 글자로 돌려준다
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 리스너가 앞 이벤트를 처리할 때까지 잠깐 기다린다. */
    private static void await(Supplier<Boolean> condition) {
        // 기다릴 마감 시각(지금 + 15초)을 정한다
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        // 마감 전까지 되풀이한다
        while (System.nanoTime() < until) {
            // 조건이 참이면(null은 거짓으로 봄)
            if (Optional.ofNullable(condition.get()).orElse(false)) {
                // 기다림을 끝낸다
                return;
            }
            // 다음 확인까지 0.2초 쉰다(인터럽트되면 그만 기다리고 실패로 끝낸다)
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            // 누가 기다림을 끊었으면
            if (Thread.currentThread().isInterrupted()) {
                // 반복을 멈춘다
                break;
            }
        }
        // 마감까지 조건이 참이 안 되면 테스트를 실패시킨다
        throw new AssertionError("조건을 기다리다 시간이 지났습니다");
    }
}
