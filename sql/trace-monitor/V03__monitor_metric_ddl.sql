-- =====================================================================
-- Trace 跟踪与监控模块 · M2 后端查询服务与 API（T2.5 指标聚合表）
-- 在 data-agent 业务库（PG，schema=public）执行；幂等可重复执行。
-- 应用启动时 MetricAggregateJob 也会自动幂等建表，两者结构保持一致。
--
-- 分钟粒度指标（5 分钟一个桶，minute=桶起点；任务每 5 分钟滚动重算最近桶）。
-- Dashboard 趋势/KPI 只查本表，不打明细表；dim_user 预留按用户切片（当前只写 NULL 全局行）。
-- =====================================================================

CREATE TABLE IF NOT EXISTS agentx_monitor_metric (
  id bigint NOT NULL,
  minute timestamp NOT NULL,       -- 桶起点（整 5 分钟）
  metric varchar(50) NOT NULL,     -- convs/rounds/fail_rounds/error_convs/avg_ms/p95_ms/prompt_tokens/completion_tokens
  dim_user varchar(100) NULL,      -- 维度可空, 支持按用户切片
  value double precision NOT NULL,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_metric ON agentx_monitor_metric (metric, minute);
