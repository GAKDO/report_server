package com.gakdo.trace.report.record.domain;

/** 체인 한 줄의 recordHash(16진수)를 서명하고 확인한다. 서명은 base64. */
public interface ChainSigner {

    String sign(String recordHashHex);

    boolean verify(String recordHashHex, String signatureBase64);
}
