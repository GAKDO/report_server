# 기능 명세: RP1 타임라인·해시 체인·검증 API

**브랜치**: `claude/project-thread-vnpr8u` (Spec Kit 기본 `feature/001-timeline-seal-chain` 대신 세션 지정 브랜치)
**작성**: 2026-11-02
**상태**: 확정(clarify 반영, 민서 "묻지 말고 진행" 지시로 추천안 채택)
**요구사항**: FR-INT-01, FR-INT-02(서버), FR-RPT-03(타임라인 데이터)
**계약**: GAKDO/contracts develop `openapi/report-server.yaml`(+ PR #11 수동 봉인), `events/trace.checkpoint.graded`·`trace.diagnostics.recorded`·`trace.capture.uploaded`·`trace.record.sealed` 1.0, `codes.yaml` 0.7.0
**입력(backlog.md)**: "렌탈의 세 시점 기록(사진·검사·진단)을 이벤트로 모아 타임라인 읽기 모델을 만들고, 기록 해시를 이전 해시에 잇는 해시 체인(서버 Ed25519 서명)을 만든다. 시점 봉인과 검증 API를 연다(FR-INT-01, FR-INT-02 서버). 수용 기준: 사진 한 장을 바꾸면 검증이 실패한다. 검증 공개 응답에는 등급·시점·해시 일치만 있고 상세는 로그인해야 본다. 봉인하면 trace 이벤트를 낸다."
**더한 결정(11/2)**: 관리자 전용 수동 봉인 API + 사용 기록(시연 우회로).

## 사용자 시나리오와 테스트

### 이야기 1 — 이벤트로 읽기 모델 만들기 (P1)
report-server는 큐 `trace.report.updates`로 `trace.capture.uploaded`(사진 객체 키)·`trace.checkpoint.graded`(등급·체인 재료)·`trace.diagnostics.recorded`(진단 해시·플래그)를 받아 시점별 읽기 모델을 채운다.
1. 같은 eventId가 두 번 와도 결과가 같다(두 번 봉인하지 않음).
2. 형식이 깨진 메시지는 `trace.dlx`(키 `trace.report.updates.dead`)로 간다.
3. 상태는 앞으로만 간다(CAPTURED → GRADED → SEALED). 봉인 뒤에 온 업로드·등급은 체인을 바꾸지 않는다.

### 이야기 2 — 봉인과 해시 체인 (P1)
등급이 오면 진단이 이미 있으면 바로, 없으면 `REPORT_SEAL_DIAGNOSTICS_WAIT`(기본 1분) 뒤 봉인한다.
1. 봉인 내용(payload) = 시점·등급·규칙 버전·신규 흠집 ID·판정 불가 면·사진(photoId, sha256)·진단(runId, sha256)·수동 여부. payloadHash = SHA-256(정규화 JSON).
2. recordHash = SHA-256(`trace-chain-1` 규칙 줄), prevHash = 앞 줄 recordHash, 서명 = Ed25519(recordHash).
3. 체인은 출고(seq 1) → 수령(2) → 반납(3). 앞 시점이 봉인되지 않았으면 기다린다.
4. 봉인하면 `trace.record.sealed`를 DB 커밋과 함께 낸다(봉인하면 trace 이벤트를 낸다).

### 이야기 3 — 검증 (P1)
1. `GET /verify/{token}`(공개): valid·기종 이름·시점별 seq·종류·등급·지문·서명 시각·검사 시각만. 서명·사진·렌탈 ID·진단 상세 없음(검증 공개 응답에는 등급·시점·해시 일치만 있고 상세는 로그인해야 본다). 없는/틀린 토큰은 404.
2. `GET /reports/rentals/{id}/chain`(ADMIN): 서명·prevHash 포함 전체와 지금 다시 계산한 valid.
3. 검증은 체인을 다시 계산하고, 봉인한 사진을 trace-captures에서 다시 본다(체크섬 또는 내려받아 SHA-256). 사진 한 장을 바꾸면 검증이 실패한다.
4. 공개 검증은 결과를 30초 둔다(`REPORT_VERIFY_CACHE_TTL`). 저장소에 닿지 못하면 체인 계산만으로 답한다.

### 이야기 4 — 타임라인 (P1)
`GET /reports/rentals/{id}`(ADMIN·본인 CUSTOMER): 시점별 상태·등급·신규 흠집 수·진단 플래그·봉인 시각·체인 상태(PENDING·VALID·BROKEN)·검증 주소(`WEB_PUBLIC_URL/verify/<token>`, 첫 봉인 전 null). 남의 렌탈 403 OUT_OF_SCOPE, 촬영 JWT 403 FORBIDDEN.

### 이야기 5 — 관리자 수동 봉인 (P2, 시연 우회로)
`POST /reports/rentals/{id}/checkpoints/{cpId}/seal`(ADMIN, 등급·사유 5~300자) → 201 ChainRecord. 같은 체인에 잇고 sealed 이벤트를 내며 사용 기록(`GET /reports/manual-seals`)에 남는다. 이미 봉인 409 RECORD_ALREADY_SEALED, 앞 시점 미봉인 409 CHECKPOINT_ORDER_VIOLATION. 수동 사실과 사유·관리자 publicId는 payload에 들어가 체인으로 보호된다.

## 범위 밖 / 결정 대기
- 진행률 SSE(RP2), 기록 검색(선택).
- 타임라인 `pins`·`modelCode`, 검증 `modelName`, 진단 `maxCapacityPct`: 지금 이벤트에 값이 없어 빈 배열·빈 문자열·null. 계약 1.1 선택 필드 추가 여부를 민서에게 물음(결정 카드).
