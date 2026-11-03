package com.gakdo.trace.report.record.infra;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.report.common.infra.PublicIds;
import com.gakdo.trace.report.record.application.RecordIngestService;
import com.gakdo.trace.report.record.application.RecordIngestService.Envelope;
import com.gakdo.trace.report.record.domain.CheckpointType;
import com.gakdo.trace.report.record.domain.Grade;
import com.gakdo.trace.report.record.domain.HashRef;
import com.gakdo.trace.report.record.domain.PhotoRef;
import com.gakdo.trace.report.record.domain.Pin;

/**
 * trace.report.updates의 이벤트 5종을 읽는다(검사 완료는 진행률용으로 봉투만). 필요한 필드만 읽고 모르는 필드는 무시한다(너그러운 소비자, 부 버전 대비).
 * 형식이 깨진 메시지는 다시 해도 소용없어 바로 trace.dlx로 보낸다.
 */
@Component
public class RecordEventListener {

    private static final Logger log = LoggerFactory.getLogger(RecordEventListener.class);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    /** 이벤트에서 SHA-256 지문을 담는 필드 이름(contracts events) */
    private static final String SHA256_FIELD = "sha256";

    private final RecordIngestService ingest;
    private final ObjectMapper mapper;

    public RecordEventListener(RecordIngestService ingest, ObjectMapper mapper) {
        this.ingest = ingest;
        this.mapper = mapper;
    }

    @RabbitListener(queues = AmqpConfig.QUEUE)
    public void onMessage(Message message) {
        handle(message.getMessageProperties().getReceivedRoutingKey(),
                new String(message.getBody(), StandardCharsets.UTF_8));
    }

    /** @return 이번에 처리했으면 true(중복·모르는 키는 false) */
    public boolean handle(String routingKey, String body) {
        if (routingKey == null) {
            return false;
        }
        JsonNode event = parse(body);
        boolean done = switch (routingKey) {
            case AmqpConfig.CAPTURE_UPLOADED -> ingest.uploaded(envelope(routingKey, event),
                    publicId(event, "captureSetId"), photos(event));
            case AmqpConfig.CHECKPOINT_GRADED -> ingest.graded(envelope(routingKey, event), graded(event));
            case AmqpConfig.DIAGNOSTICS_RECORDED -> ingest.diagnostics(envelope(routingKey, event),
                    new RecordIngestService.Diagnostics(publicId(event, "runId"), sha256(event, SHA256_FIELD),
                            flagKinds(event), maxCapacityPct(event)));
            case AmqpConfig.CHECKPOINT_STARTED -> ingest.started(envelope(routingKey, event),
                    new RecordIngestService.Started(publicId(event, "deviceId"), text(event, "serial", 64),
                            text(event, "modelCode", 64), text(event, "modelName", 128)));
            case AmqpConfig.INSPECT_COMPLETED -> ingest.inspected(envelope(routingKey, event));
            default -> {
                log.warn("받지 않는 라우팅 키를 건너뜁니다: {}", routingKey);
                yield false;
            }
        };
        if (log.isInfoEnabled()) {
            log.info("이벤트 {} eventId={} checkpointId={}{}", routingKey, event.path("eventId").asText(),
                    event.path("checkpointId").asText(), done ? "" : " (건너뜀)");
        }
        return done;
    }

    private Envelope envelope(String routingKey, JsonNode event) {
        OffsetDateTime occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(event.path("occurredAt").asText());
        } catch (DateTimeParseException e) {
            throw reject("봉투의 occurredAt이 올바르지 않습니다");
        }
        return new Envelope(publicId(event, "eventId"), routingKey, publicId(event, "rentalId"),
                publicId(event, "checkpointId"), occurredAt);
    }

    private RecordIngestService.Graded graded(JsonNode event) {
        List<HashRef> photos = new ArrayList<>();
        for (JsonNode p : event.path("photos")) {
            photos.add(new HashRef(publicId(p, "photoId"), sha256(p, SHA256_FIELD)));
        }
        return new RecordIngestService.Graded(publicId(event, "captureSetId"),
                enumValue(CheckpointType.class, event, "checkpointType"), enumValue(Grade.class, event, "grade"),
                text(event, "ruleVersion"), strings(event.path("newDefectIds")),
                strings(event.path("unjudgeableFaces")), photos, pins(event));
    }

    /** 1.1 선택 필드. 없으면(1.0) 빈 배열 */
    private static List<Pin> pins(JsonNode event) {
        List<Pin> pins = new ArrayList<>();
        JsonNode array = event.path("pins");
        if (array.isMissingNode() || array.isNull()) {
            return pins;
        }
        if (!array.isArray()) {
            throw reject("pins가 배열이 아닙니다");
        }
        for (JsonNode p : array) {
            double x = number(p, "x");
            double y = number(p, "y");
            if (x < 0 || y < 0) {
                throw reject("핀 좌표가 음수입니다");
            }
            if (!p.path("isNew").isBoolean()) {
                throw reject("핀의 isNew가 참·거짓이 아닙니다");
            }
            pins.add(new Pin(publicId(p, "defectId"), text(p, "face"), x, y, p.path("isNew").asBoolean()));
        }
        return pins;
    }

    /** 1.1 선택 필드. 없거나 null이면 null(배터리 없음·읽지 못함) */
    private static Double maxCapacityPct(JsonNode event) {
        JsonNode value = event.path("maxCapacityPct");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isNumber() || value.asDouble() < 0 || value.asDouble() > 200) {
            throw reject("maxCapacityPct가 0~200 숫자가 아닙니다");
        }
        return value.asDouble();
    }

    private static double number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            throw reject(field + "가 숫자가 아닙니다");
        }
        return value.asDouble();
    }

    private List<PhotoRef> photos(JsonNode event) {
        List<PhotoRef> photos = new ArrayList<>();
        for (JsonNode p : event.path("photos")) {
            photos.add(new PhotoRef(publicId(p, "photoId"), text(p, "face"), text(p, "light"), text(p, "camera"),
                    text(p, "objectKey"), sha256(p, SHA256_FIELD)));
        }
        return photos;
    }

    private static List<String> flagKinds(JsonNode event) {
        List<String> kinds = new ArrayList<>();
        event.path("flags").forEach(f -> kinds.add(f.path("kind").asText()));
        return kinds.stream().distinct().sorted().toList();
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw reject("이벤트가 JSON 객체가 아닙니다");
            }
            return node;
        } catch (JsonProcessingException e) {
            throw reject("이벤트 JSON을 읽을 수 없습니다");
        }
    }

    private static String publicId(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (!PublicIds.isValid(value)) {
            throw reject(field + "가 올바르지 않습니다");
        }
        return value;
    }

    private static String sha256(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (!SHA256.matcher(value).matches()) {
            throw reject(field + "가 SHA-256 16진수가 아닙니다");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw reject(field + "가 비어 있습니다");
        }
        return value;
    }

    /** 저장 열 길이(V3)를 넘는 값은 다시 해도 실패하므로 바로 거절한다. */
    private static String text(JsonNode node, String field, int maxLength) {
        String value = text(node, field);
        if (value.length() > maxLength) {
            throw reject(field + "가 " + maxLength + "자를 넘습니다");
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, JsonNode node, String field) {
        try {
            return Enum.valueOf(type, text(node, field));
        } catch (IllegalArgumentException e) {
            throw reject(field + " 값이 코드에 없습니다");
        }
    }

    private static AmqpRejectAndDontRequeueException reject(String reason) {
        return new AmqpRejectAndDontRequeueException(reason);
    }
}
