package com.gakdo.trace.report.record.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 해시를 매길 JSON을 한 가지 글자열로 만든다: 객체 키는 사전순, 공백 없음, 문자열은 JSON 표준 이스케이프(UTF-8 그대로).
 * PostgreSQL jsonb는 키 순서와 공백을 바꿔 저장하므로, 저장한 payload를 다시 읽어도 같은 해시가 나오게 하려는 것이다.
 * 실수(float)는 쓰지 않는다(표현이 하나로 정해지지 않음). 봉인 내용에는 정수·문자열·불리언·null만 넣는다.
 */
public final class CanonicalJson {

    private static final ObjectMapper STRINGS = new ObjectMapper();

    private CanonicalJson() {
    }

    public static String write(JsonNode node) {
        StringBuilder out = new StringBuilder();
        append(node, out);
        return out.toString();
    }

    private static void append(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            appendObject(node, out);
        } else if (node.isArray()) {
            appendArray(node, out);
        } else if (node.isTextual()) {
            out.append(quote(node.textValue()));
        } else if (node.isIntegralNumber() || node.isBoolean()) {
            out.append(node.asText());
        } else {
            throw new IllegalArgumentException("봉인 내용에는 정수·문자열·불리언·null만 넣습니다: " + node.getNodeType());
        }
    }

    /** 객체: 키 사전순, {"키":값,...} */
    private static void appendObject(JsonNode node, StringBuilder out) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(node.properties());
        fields.sort(Map.Entry.comparingByKey());
        out.append('{');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(quote(fields.get(i).getKey())).append(':');
            append(fields.get(i).getValue(), out);
        }
        out.append('}');
    }

    /** 배열: 순서 그대로, [값,...] */
    private static void appendArray(JsonNode node, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            append(node.get(i), out);
        }
        out.append(']');
    }

    private static String quote(String value) {
        try {
            return STRINGS.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
