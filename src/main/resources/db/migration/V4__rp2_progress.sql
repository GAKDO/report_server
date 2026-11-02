-- RP2 검사 진행률: inspect-worker의 trace.inspect.completed를 받은 시각. 다시 붙은 화면에 '검사 끝' 단계를 보여 주려고 둔다.
-- 화면용이라 해시 체인 재료(trace-chain-1)는 그대로다.
ALTER TABLE report.checkpoint_view
    ADD COLUMN inspected_at timestamptz;
