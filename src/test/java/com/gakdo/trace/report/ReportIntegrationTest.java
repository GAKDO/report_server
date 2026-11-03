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
@SpringBootTest(properties = {"app.chain.diagnostics-wait=PT1H", "app.verify.cache-ttl=PT0S",
        "app.chain.sweep-interval=PT1H", "app.web-public-url=https://gakdo.site"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReportIntegrationTest {

    private static final String BUCKET = "trace-captures";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("trace");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    /** MinIO 대신 moto(S3 호환, mirror.gcr.io). capture-server CI와 같은 이미지 */
    static final GenericContainer<?> S3 = new GenericContainer<>("mirror.gcr.io/motoserver/moto:5.1.16")
            .withExposedPorts(5000)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        S3.start();
        registry.add("app.storage.endpoint", () -> "http://" + S3.getHost() + ":" + S3.getMappedPort(5000));
        registry.add("app.storage.access-key", () -> "test");
        registry.add("app.storage.secret-key", () -> "test");
        registry.add("app.chain.signing-key", TestKeys::ed25519PemBase64);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private S3Client s3;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private SealService seals;

    private final Random random = new Random(11);

    @Test
    @DisplayName("이벤트로 시점을 봉인해 체인을 잇고, 공개 검증은 등급·지문만 보이며, 사진 한 장을 바꾸면 검증이 실패하고, 수동 봉인은 기록이 남는다")
    void endToEnd() throws Exception {
        s3.createBucket(b -> b.bucket(BUCKET));
        Queue sealedQueue = new Queue("test.record.sealed", false, false, false);
        rabbitAdmin.declareQueue(sealedQueue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(sealedQueue).to(new TopicExchange("trace.events"))
                .with("trace.record.sealed"));

        String rental = PublicIds.next();
        String out = PublicIds.next();
        String rcv = PublicIds.next();
        String ret = PublicIds.next();

        OutSealed sealedOut = sealOutAndCheckTimeline(rental, out);
        List<String[]> rcvPhotos = sealRcvAfterWaiting(rental, rcv, sealedOut.event());
        checkPublicVerification(sealedOut.verifyPath(), rental, sealedOut.firstPhotoId());
        checkChangedPhotoBreaksChain(rental, sealedOut.verifyPath(), rcvPhotos.get(1));
        checkManualSeal(rental, ret);
        checkOrderViolationAndUnknownRental();
    }

    /** 출고 봉인 결과: 봉인 이벤트, 공개 검증 경로, 첫 사진 ID(공개 응답에 나오면 안 됨) */
    private record OutSealed(JsonNode event, String verifyPath, String firstPhotoId) {
    }

    /** ── 출고: 시점 시작(기종, 1.1)·진단(배터리 최대 용량, 1.1)이 먼저 오고, 업로드·등급(핀, 1.1)이 오면 바로 봉인 ── */
    private OutSealed sealOutAndCheckTimeline(String rental, String out) throws Exception {
        send("trace.checkpoint.started", envelope11(rental, out) + ",\"checkpointType\":\"OUT\",\"deviceId\":\""
                + PublicIds.next() + "\",\"serial\":\"TEST-SERIAL-1\",\"modelCode\":\"MBA13-M2\""
                + ",\"modelName\":\"MacBook Air (M2, 2022)\"");
        send("trace.diagnostics.recorded", envelope11(rental, out) + ",\"runId\":\"" + PublicIds.next() + "\",\"sha256\":\""
                + sha("diag-out") + "\",\"baseRunId\":null,\"flags\":[],\"maxCapacityPct\":91.2");
        List<String[]> outPhotos = uploadPhotos(rental, out, 2);
        String pinId = PublicIds.next();
        String gradedOut = envelope11(rental, out) + gradedBody("OUT", "A", outPhotos)
                + ",\"pins\":[{\"defectId\":\"" + pinId + "\",\"face\":\"TOP\",\"x\":812.5,\"y\":403.0,\"isNew\":true}]";
        send("trace.checkpoint.graded", gradedOut);
        JsonNode sealed1 = receiveSealed();
        assertThat(sealed1.path("seq").asInt()).isEqualTo(1);
        assertThat(sealed1.path("checkpointType").asText()).isEqualTo("OUT");
        assertThat(sealed1.path("prevHash").isNull()).isTrue();
        assertThat(sealed1.path("schemaVersion").asText()).isEqualTo("1.0");

        // 같은 eventId가 다시 와도 두 번 봉인하지 않는다
        send("trace.checkpoint.graded", gradedOut);
        assertThat(rabbit.receive("test.record.sealed", 1500)).isNull();

        JsonNode timeline = getJson("/api/v1/reports/rentals/" + rental);
        assertThat(timeline.path("chainStatus").asText()).isEqualTo("VALID");
        assertThat(timeline.path("checkpoints").get(0).path("status").asText()).isEqualTo("SEALED");
        assertThat(timeline.path("modelCode").asText()).isEqualTo("MBA13-M2");
        JsonNode outView = timeline.path("checkpoints").get(0);
        assertThat(outView.path("pins")).hasSize(1);
        assertThat(outView.path("pins").get(0).path("defectId").asText()).isEqualTo(pinId);
        assertThat(outView.path("pins").get(0).path("x").asDouble()).isEqualTo(812.5);
        assertThat(outView.path("pins").get(0).path("isNew").asBoolean()).isTrue();
        assertThat(outView.path("diagnosticsSummary").path("maxCapacityPct").asDouble()).isEqualTo(91.2);
        String verifyUrl = timeline.path("verifyUrl").asText();
        assertThat(verifyUrl).matches("https://gakdo\\.site/verify/[0-9a-f]{32}");
        String verifyPath = "/api/v1/verify/" + verifyUrl.substring(verifyUrl.lastIndexOf('/') + 1);
        return new OutSealed(sealed1, verifyPath, outPhotos.get(0)[0]);
    }

    /** ── 수령: 진단 없이 등급만 → 기다렸다가(스케줄러가 부르는 것과 같게) 봉인, 앞 기록 해시에 잇는다 ── */
    private List<String[]> sealRcvAfterWaiting(String rental, String rcv, JsonNode sealed1) throws Exception {
        List<String[]> rcvPhotos = uploadPhotos(rental, rcv, 2);
        send("trace.checkpoint.graded", envelope(rental, rcv) + gradedBody("RCV", "B", rcvPhotos));
        assertThat(rabbit.receive("test.record.sealed", 1500)).isNull();
        await(() -> seals.sealIfReady(rcv, Times.now().plusHours(2)).isPresent());
        JsonNode sealed2 = receiveSealed();
        assertThat(sealed2.path("seq").asInt()).isEqualTo(2);
        JsonNode chain = getJson("/api/v1/reports/rentals/" + rental + "/chain");
        assertThat(chain.path("valid").asBoolean()).isTrue();
        assertThat(chain.path("records")).hasSize(2);
        assertThat(sealed2.path("prevHash").asText()).isNotBlank().isNotEqualTo(sealed1.path("payloadHash").asText());
        return rcvPhotos;
    }

    /** 공개 검증: 등급·시점·지문·서명 시각만 */
    private void checkPublicVerification(String verifyPath, String rental, String outPhotoId) throws Exception {
        JsonNode verify = json.readTree(mvc.perform(get(verifyPath)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(verify.path("valid").asBoolean()).isTrue();
        assertThat(verify.path("modelName").asText()).isEqualTo("MacBook Air (M2, 2022)");
        assertThat(verify.path("records").get(1).path("grade").asText()).isEqualTo("B");
        assertThat(verify.path("records").get(0).has("signature")).isFalse();
        assertThat(verify.toString()).doesNotContain(rental).doesNotContain(outPhotoId);
    }

    /** ── 사진 한 장을 바꾸면 검증이 실패한다(되돌리면 다시 맞다) ── */
    private void checkChangedPhotoBreaksChain(String rental, String verifyPath, String[] photo) throws Exception {
        s3.putObject(b -> b.bucket(BUCKET).key(photo[2]), RequestBody.fromBytes("바꾼 사진".getBytes()));
        JsonNode broken = json.readTree(mvc.perform(get(verifyPath)).andReturn().getResponse().getContentAsString());
        assertThat(broken.path("valid").asBoolean()).isFalse();
        assertThat(getJson("/api/v1/reports/rentals/" + rental).path("chainStatus").asText()).isEqualTo("BROKEN");
        s3.putObject(b -> b.bucket(BUCKET).key(photo[2]), RequestBody.fromBytes(bytesOf(photo)));
        assertThat(getJson("/api/v1/reports/rentals/" + rental + "/chain").path("valid").asBoolean()).isTrue();
    }

    /** ── 반납: 파이프라인 없이 관리자 수동 봉인(사용 기록) ── */
    private void checkManualSeal(String rental, String ret) throws Exception {
        String sealUrl = "/api/v1/reports/rentals/" + rental + "/checkpoints/" + ret + "/seal";
        String body = "{\"checkpointType\":\"RET\",\"grade\":\"C\",\"reason\":\"집 PC 꺼짐, 육안 확인 후 봉인\"}";
        mvc.perform(post(sealUrl).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        assertThat(receiveSealed().path("seq").asInt()).isEqualTo(3);
        mvc.perform(post(sealUrl).with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        JsonNode log = getJson("/api/v1/reports/manual-seals");
        assertThat(log.path("items").get(0).path("checkpointId").asText()).isEqualTo(ret);
        assertThat(log.path("items").get(0).path("sealedBy").asText()).isEqualTo("admin0000000000000001");
        assertThat(getJson("/api/v1/reports/rentals/" + rental + "/chain").path("valid").asBoolean()).isTrue();
    }

    /** 앞 시점(출고)이 없는 렌탈의 수령을 수동 봉인하면 409, 모르는 렌탈은 404 */
    private void checkOrderViolationAndUnknownRental() throws Exception {
        String other = PublicIds.next();
        mvc.perform(post("/api/v1/reports/rentals/" + other + "/checkpoints/" + PublicIds.next() + "/seal").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"checkpointType\":\"RCV\",\"grade\":\"A\",\"reason\":\"순서 위반 시험\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/reports/rentals/" + PublicIds.next()).with(admin())).andExpect(status().isNotFound());
    }

    // ── 도우미 ──

    private final java.util.Map<String, byte[]> files = new java.util.HashMap<>();

    /** 사진을 저장소에 올리고 trace.capture.uploaded를 보낸다. [photoId, sha256, objectKey] */
    private List<String[]> uploadPhotos(String rental, String checkpoint, int count) throws Exception {
        String setId = PublicIds.next();
        List<String[]> photos = new ArrayList<>();
        StringBuilder items = new StringBuilder();
        String[] faces = {"TOP", "BOT"};
        for (int i = 0; i < count; i++) {
            byte[] bytes = new byte[1024 + random.nextInt(2048)];
            random.nextBytes(bytes);
            String id = PublicIds.next();
            String key = "captures/" + setId + "/" + faces[i] + "-L1-CAM1.jpg";
            s3.putObject(b -> b.bucket(BUCKET).key(key), RequestBody.fromBytes(bytes));
            files.put(id, bytes);
            String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            photos.add(new String[] {id, sha, key, setId});
            items.append(i > 0 ? "," : "").append("{\"photoId\":\"").append(id).append("\",\"face\":\"").append(faces[i])
                    .append("\",\"light\":\"L1\",\"camera\":\"CAM1\",\"objectKey\":\"").append(key)
                    .append("\",\"sha256\":\"").append(sha).append("\"}");
        }
        send("trace.capture.uploaded", envelope(rental, checkpoint) + ",\"captureSetId\":\"" + setId
                + "\",\"source\":\"BOOTH\",\"needDetection\":false,\"photos\":[" + items + "]");
        return photos;
    }

    private byte[] bytesOf(String[] photo) {
        return files.get(photo[0]);
    }

    private static String gradedBody(String type, String grade, List<String[]> photos) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < photos.size(); i++) {
            items.append(i > 0 ? "," : "").append("{\"photoId\":\"").append(photos.get(i)[0]).append("\",\"sha256\":\"")
                    .append(photos.get(i)[1]).append("\"}");
        }
        return ",\"captureSetId\":\"" + photos.get(0)[3] + "\",\"checkpointType\":\"" + type + "\",\"grade\":\"" + grade
                + "\",\"ruleVersion\":\"grade-rule-1.0\",\"newDefectIds\":[],\"unjudgeableFaces\":[\"SCR1\"],\"photos\":["
                + items + "]";
    }

    private static String envelope(String rental, String checkpoint) {
        return "{\"schemaVersion\":\"1.0\",\"eventId\":\"" + PublicIds.next() + "\",\"occurredAt\":\"2026-11-12T09:00:00+09:00\""
                + ",\"rentalId\":\"" + rental + "\",\"checkpointId\":\"" + checkpoint + "\"";
    }

    private static String envelope11(String rental, String checkpoint) {
        return envelope(rental, checkpoint).replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"1.1\"");
    }

    private void send(String routingKey, String partial) {
        Message message = MessageBuilder.withBody((partial + "}").getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
        rabbit.send("trace.events", routingKey, message);
    }

    private JsonNode receiveSealed() throws Exception {
        Message m = rabbit.receive("test.record.sealed", 15_000);
        assertThat(m).as("trace.record.sealed").isNotNull();
        return json.readTree(m.getBody());
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url).with(admin())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }

    private static String sha(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 리스너가 앞 이벤트를 처리할 때까지 잠깐 기다린다. */
    private static void await(Supplier<Boolean> condition) {
        long until = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < until) {
            if (Optional.ofNullable(condition.get()).orElse(false)) {
                return;
            }
            // 다음 확인까지 0.2초 쉰다(인터럽트되면 그만 기다리고 실패로 끝낸다)
            LockSupport.parkNanos(Duration.ofMillis(200).toNanos());
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
        }
        throw new AssertionError("조건을 기다리다 시간이 지났습니다");
    }
}
