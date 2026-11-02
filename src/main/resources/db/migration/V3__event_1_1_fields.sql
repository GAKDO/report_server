-- 이벤트 1.1 선택 필드(11/2 결정): 흠집 핀, 배터리 최대 용량, 기기·기종(checkpoint.started).
-- 모두 화면용이라 해시 체인 재료(trace-chain-1)는 그대로다.
ALTER TABLE report.checkpoint_view
    ADD COLUMN pins jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN diag_max_capacity_pct double precision;

ALTER TABLE report.report
    ADD COLUMN device_id varchar(21),
    ADD COLUMN serial varchar(64),
    ADD COLUMN model_code varchar(32),
    ADD COLUMN model_name varchar(128);
