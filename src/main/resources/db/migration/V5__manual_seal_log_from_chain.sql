-- 정규화(11/20 ERD 점검): 수동 봉인 사용 기록이 체인 한 줄과 같은 값을 다시 들고 있던 것을 푼다.
-- manual_seal_log의 rental_id·checkpoint_type·grade·seq는 같은 시점(checkpoint_id)의 record_chain 줄에 이미 있다
-- (같은 트랜잭션에서 같은 값으로 두 번 저장). 기록은 사유·누가만 들고 나머지는 체인에서 읽는다.
-- 헌법 "삭제는 다음 배포": 이번에는 NULL만 허용해 새 코드(칸을 안 채움)와 이전 코드(칸을 채움)가 모두 돌게 하고,
-- 네 칸은 이 버전이 배포된 다음 마이그레이션에서 지운다.
ALTER TABLE report.manual_seal_log
  ALTER COLUMN rental_id DROP NOT NULL,
  ALTER COLUMN checkpoint_type DROP NOT NULL,
  ALTER COLUMN grade DROP NOT NULL,
  ALTER COLUMN seq DROP NOT NULL;

-- 기록은 체인에 있는 시점만 가리킨다. 시험 렌탈 지우기는 체인 줄을 지우면 기록도 함께 지워진다
-- (이전 코드는 체인을 먼저 지우므로 CASCADE로 둔다).
-- 이미 있는 행 때문에 서버가 못 뜨는 일이 없도록 NOT VALID로 더한 뒤 확인을 시도한다.
ALTER TABLE report.manual_seal_log ADD CONSTRAINT fk_manual_seal_log_chain
  FOREIGN KEY (checkpoint_id) REFERENCES report.record_chain(checkpoint_id) ON DELETE CASCADE NOT VALID;

DO $$
BEGIN
  ALTER TABLE report.manual_seal_log VALIDATE CONSTRAINT fk_manual_seal_log_chain;
EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'manual_seal_log에 체인 줄이 없는 기록이 있습니다. 새 기록만 확인합니다.';
END $$;

-- report.report_view(V1)는 RP1부터 쓰지 않는다(checkpoint_view로 바뀜). 이번에 코드가 건드리지 않게 했고
-- 표는 다음 마이그레이션에서 지운다(이전 코드의 시험 렌탈 지우기가 이 표를 지우기 때문).
