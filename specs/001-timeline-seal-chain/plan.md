# 구현 계획: RP1

Java 21, Spring Boot 3.5.7, Spring Web MVC·Data JPA·AMQP·OAuth2 리소스 서버, Flyway(report 스키마), BouncyCastle Ed25519, AWS SDK v2 S3(검증 때 사진 재대조). 테스트는 JUnit 5 + MockMvc + Testcontainers(PostgreSQL·RabbitMQ·moto S3, `disabledWithoutDocker`).

## 구조 (`com.gakdo.trace.report`)
- `common/{api,domain,infra}`: Problem Details·ErrorCode·PublicIds·Times·SecurityConfig·Caller(렌탈 범위)
- `record/domain`: CheckpointView(읽기 모델), ChainRecord, RentalReport(검증 토큰·잠금), ManualSealLog, CanonicalJson, ChainHashes(`trace-chain-1`), SealPayload, ChainCheck, ChainSigner
- `record/application`: RecordIngestService(멱등·읽기 모델), SealService(자동·수동 봉인), SealScheduler(진단 대기 뒤 봉인), ChainVerifier(재계산 + 저장소 재대조 + 캐시), ReportQueryService
- `record/infra`: AmqpConfig(큐 trace.report.updates, DLX), RecordEventListener, RabbitSealedEventPublisher(채널 트랜잭션), Ed25519ChainSigner, S3PhotoStore, 저장소
- `record/api`: ReportController, VerifyController, ReportDtos

## 설정 (환경 변수)
`CHAIN_SIGNING_KEY`(trace-secrets, `openssl genpkey -algorithm ed25519 | base64 -w0`), `WEB_PUBLIC_URL`, `MINIO_ENDPOINT`·`MINIO_ACCESS_KEY`·`MINIO_SECRET_KEY`, `REPORT_SEAL_DIAGNOSTICS_WAIT`(PT1M), `REPORT_VERIFY_CACHE_TTL`(PT30S), `REPORT_VERIFY_CHECK_OBJECTS`(true). 키가 없으면 기동 실패(`CHAIN_ALLOW_EPHEMERAL_KEY=true`는 개발 전용).

## 동시성·멱등
- 봉인은 `report.report` 줄을 `SELECT … FOR UPDATE`로 잠근 뒤 체인 끝을 읽는다. `(rental_id, seq)`·`checkpoint_id`·`(rental_id, checkpoint_type)` UNIQUE가 마지막 방어선.
- eventId는 `report.processed_event`에 같은 트랜잭션으로 넣는다(ON CONFLICT DO NOTHING).

## 헌법 확인
계약 우선(수동 봉인은 contracts PR #11 먼저), 비밀은 환경 변수, 서비스 간 REST 없음(이벤트만), 개인정보 없음(payload에 이메일·이름 없음), PublicId만 밖으로.
