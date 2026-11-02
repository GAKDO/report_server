# 작업: RP1

- [x] T001 contracts: 수동 봉인 경로·사용 기록·RECORD_ALREADY_SEALED (contracts PR #11)
- [x] T002 pom 의존성(JPA·AMQP·리소스 서버·Flyway·BouncyCastle·S3), application.yml 환경 변수
- [x] T003 Flyway V1(docs 절)·V2(체인 재료·읽기 모델·멱등·수동 기록)
- [x] T004 공통: Problem Details·ErrorCode·Caller·SecurityConfig(역할×경로)
- [x] T005 도메인: CanonicalJson·ChainHashes·SealPayload·ChainCheck + 단위 시험(사진 바꾸면 실패·위변조 탐지)
- [x] T006 Ed25519 서명(PKCS#8 base64/PEM, 키 없으면 기동 실패)
- [x] T007 이벤트 수신(멱등·DLX)·읽기 모델
- [x] T008 자동 봉인(진단 대기·순서)·스케줄러·trace.record.sealed(채널 트랜잭션)
- [x] T009 검증(재계산 + 저장소 재대조 + 캐시)·타임라인·체인·공개 검증 API + MockMvc 시험
- [x] T010 수동 봉인 + 사용 기록 API
- [x] T011 통합 시험(PostgreSQL·RabbitMQ·S3): 봉인 → 검증 → 사진 바꿔치기 실패 → 수동 봉인
- [ ] T012 gateway access-policy 재생성(contracts #11 머지 뒤)
- [ ] T013 (결정 뒤) 핀·기종 이름·배터리 용량 채우기
