# 데이터 모델: report 스키마

V1 = GAKDO/docs `db/V1__init.sql` report 절(record_chain·report·report_view). V2(RP1)가 더하는 것:

| 테이블 | 칸 | 뜻 |
| --- | --- | --- |
| record_chain | checkpoint_type, grade, payload(jsonb), record_hash, manual | 봉인 원문·줄 해시(다음 prev_hash)·수동 여부. UNIQUE(checkpoint_id), (rental_id, checkpoint_type) |
| checkpoint_view | rental_id, checkpoint_id(UNIQUE), checkpoint_type, status, occurred_at, capture_set_id, photos, chain_photos, grade, rule_version, new_defect_ids, unjudgeable_faces, graded_at, diag_run_id, diag_sha256, diag_flags, sealed_at | 이벤트로 만드는 시점 읽기 모델 |
| processed_event | event_id(UNIQUE), routing_key | 멱등 |
| manual_seal_log | rental_id, checkpoint_id(UNIQUE), checkpoint_type, grade, seq, reason, sealed_by | 수동 봉인 사용 기록 |

report_view(timeline jsonb)는 V1에 있지만 RP1은 checkpoint_view 행에서 바로 만든다(쓰지 않음).

## 해시 규칙 `trace-chain-1`
- payloadHash = SHA-256(정규화 JSON(payload)) — 키 사전순, 공백 없음, 정수·문자열·불리언·null만
- recordHash = SHA-256(`trace-chain-1\n{seq}\n{rentalId}\n{checkpointId}\n{checkpointType}\n{payloadHash}\n{prevHash 또는 빈칸}\n{signedAt UTC yyyy-MM-ddTHH:mm:ss.SSSSSSZ}`)
- 서명 = Ed25519(recordHash 32바이트), base64. 공개 키는 기동 로그에 남긴다.
