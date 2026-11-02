package com.gakdo.trace.report.record.domain;

/** trace.capture.uploaded의 사진 한 장. objectKey는 검증 때 저장소에서 다시 해시를 보려고 둔다. */
public record PhotoRef(String photoId, String face, String light, String camera, String objectKey, String sha256) {
}
