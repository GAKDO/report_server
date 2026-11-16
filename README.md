# report-server

TRACE(노트북 렌탈 상태 기록 서비스)의 **report-server**: 타임라인 읽기 모델·해시 체인·검증·SSE 진행률

- 담당: A
- 설계: [GAKDO/docs](https://github.com/GAKDO/docs) — 요구사항·아키텍처·ERD·REST API v0
- 기술: Java 21, Spring Boot 3.5.7, Maven

## 실행

```bash
./mvnw spring-boot:run
```

```bash
curl http://localhost:8080/api/v1/reports/ping
```

## 설정 (환경 변수)

| 이름 | 어디서 | 뜻 |
|---|---|---|
| `CHAIN_SIGNING_KEY` | trace-secrets | 해시 체인 Ed25519 개인 키. `openssl genpkey -algorithm ed25519 \| base64 -w0` (없으면 기동 실패) |
| `WEB_PUBLIC_URL` | infra.env | 검증 주소 `<WEB_PUBLIC_URL>/verify/<token>` |
| `MINIO_ENDPOINT`·`MINIO_ACCESS_KEY`·`MINIO_SECRET_KEY` | infra.env·trace-secrets | 검증 때 봉인한 사진(trace-captures)을 다시 본다 |
| `REPORT_SEAL_DIAGNOSTICS_WAIT` | 선택(PT1M) | 등급이 나온 뒤 진단을 기다리는 시간 |
| `REPORT_VERIFY_CACHE_TTL` | 선택(PT30S) | 공개 검증 결과를 두는 시간 |
| `CHAIN_ALLOW_EPHEMERAL_KEY` | 선택(false) | 키 없이 띄우는 개발·시험용(다시 시작하면 이전 서명 검증이 실패) |
| `REDIS_HOST`·`REDIS_PORT`·`REDIS_DATABASE`·`REDIS_PASSWORD`, `REPORT_PROGRESS_REDIS_ENABLED` | infra.env·trace-secrets, 선택(false) | inspect-worker 진행률(Redis 채널)을 구독해 SSE로 보냄. 끄면 이벤트 단계(20·60·80·100)만 보냄 |
| `REPORT_SEAL_SWEEP_INTERVAL`·`REPORT_VERIFY_CHECK_OBJECTS` | 선택(PT15S·true) | 봉인 대기 확인 주기, 검증 때 사진 해시까지 다시 볼지 |

해시 규칙은 `specs/001-timeline-seal-chain/data-model.md`(`trace-chain-1`)에 있습니다. 봉인된 기록은 누구나 `<WEB_PUBLIC_URL>/verify/<token>`에서 해시 체인과 Ed25519 서명을 다시 계산해 확인합니다.

## 테스트

```bash
./mvnw verify
```

줄 커버리지 40% 미만이면 실패합니다(블로그와 같은 기준).

## CI/CD

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `backend-ci` | PR, develop push | 테스트 + JaCoCo 40% + (켜져 있으면) 학교 SonarQube |
| `배포` | PR(빌드만), develop·main push(`.md`만 바뀌면 건너뜀) | 이미지 → GHCR(`ghcr.io/<조직>/report-server`, develop은 `:develop`). 지금 운영(https://gakdo.site, 학교 서버 Compose)은 gitops `compose/ops/autodeploy.sh`가 3분마다 `:develop`을 받아 이 서비스만 무중단으로 바꾼다. main이면 gitops `overlays/school` 이미지 태그도 바꾼다(클러스터 경로, 지금은 쓰지 않음) |
| `비밀값 검사` | PR, main·develop push | 학교 공용 비밀번호 패턴 + gitleaks |
| `release` | main에 CHANGELOG 버전 | 태그와 GitHub Release |
| `PR 알림`·`CI 결과 알림` | PR·CI 끝 | Discord·텔레그램 (변수로 켬) |

브랜치 규칙과 릴리스 절차는 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.
