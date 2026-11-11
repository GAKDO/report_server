package com.gakdo.trace.report.record.domain;

import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 시점 하나를 봉인할 때 묶는 내용. 사진·진단 JSON 해시와 등급·규칙 버전을 넣고, 순서는 정렬해 같은 재료면 같은 해시가 나오게 한다.
 * 개인정보는 넣지 않는다(이메일·이름 없음, 수동 봉인은 관리자 계정 publicId와 사유만).
 */
public final class SealPayload {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private SealPayload() {
    }

    /** 수동 봉인 정보. 자동 봉인은 null. */
    public record Manual(String sealedBy, String reason) {
    }

    public static ObjectNode of(CheckpointView view, CheckpointType type, Grade grade, Manual manual) {
        ObjectNode root = JSON.objectNode();
        root.put("rule", ChainHashes.RULE);
        root.put("rentalId", view.rentalId());
        root.put("checkpointId", view.checkpointId());
        root.put("checkpointType", type.name());
        root.put("captureSetId", view.captureSetId());
        root.put("grade", grade.name());
        root.put("ruleVersion", view.ruleVersion());
        root.set("newDefectIds", sortedStrings(view.newDefectIds()));
        root.set("unjudgeableFaces", sortedStrings(view.unjudgeableFaces()));
        ArrayNode photos = root.putArray("photos");
        materials(view).stream()
                .sorted(Comparator.comparing(HashRef::photoId))
                .forEach(p -> photos.addObject().put("photoId", p.photoId()).put("sha256", p.sha256()));
        if (view.hasDiagnostics()) {
            root.putObject("diagnostics").put("runId", view.diagRunId()).put("sha256", view.diagSha256());
        } else {
            root.putNull("diagnostics");
        }
        if (manual == null) {
            root.putNull("manual");
        } else {
            root.putObject("manual").put("sealedBy", manual.sealedBy()).put("reason", manual.reason());
        }
        return root;
    }

    /** 등급 이벤트의 사진 목록이 체인 재료다. 등급 없이 수동 봉인하면 업로드 이벤트의 사진을 쓴다. */
    static List<HashRef> materials(CheckpointView view) {
        if (!view.chainPhotos().isEmpty()) {
            return view.chainPhotos();
        }
        return view.photos().stream().map(p -> new HashRef(p.photoId(), p.sha256())).toList();
    }

    private static ArrayNode sortedStrings(List<String> values) {
        ArrayNode array = JSON.arrayNode();
        values.stream().sorted().forEach(array::add);
        return array;
    }
}
