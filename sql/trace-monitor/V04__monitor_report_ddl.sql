-- =====================================================================
-- Trace 跟踪与监控模块 · M5 AI 诊断与报告（T5.5 报告落库表）
-- 在 data-agent 业务库（PG，schema=public）执行；幂等可重复执行。
-- 应用启动时 MonitorReportStore 也会自动幂等建表，两者结构保持一致。
-- =====================================================================

CREATE TABLE IF NOT EXISTS agentx_monitor_report (
  id bigint NOT NULL,
  type varchar(20) NOT NULL,               -- single(单会话诊断) / daily(日报)
  conversation_id varchar(100) NULL,       -- single 类型填, daily 为 NULL
  period_start timestamp NULL,             -- 日报周期起点
  period_end timestamp NULL,               -- 日报周期终点
  status varchar(20) NOT NULL,             -- running / done / failed
  summary_md text NULL,                    -- 摘要（归因行 + 前 200 字，列表页展示）
  detail_md text NULL,                     -- markdown 报告全文
  metrics_json jsonb NULL,                 -- 结构化指标（归因/置信度/影响轮/窗口 KPI 等）
  created_by varchar(20) NOT NULL,         -- manual(手动触发) / auto(定时日报)
  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_report_conv ON agentx_monitor_report (conversation_id);
