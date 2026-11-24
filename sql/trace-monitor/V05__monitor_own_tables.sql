-- =====================================================================
-- 监控中心自有表（conversation / session / trace 重建）· 数据层（T1）
-- 方案：docs/trace追溯设计/监控中心自有表方案.md §3
-- 在 data-agent 业务库（PG，schema=public）执行；幂等可重复执行。
--
-- 新建三张监控自有表（D2）：
--   agentx_monitor_conversation  对话窗口（一窗口一行，列表页主表，物化聚合列）
--   agentx_monitor_session       提问（一次提问一行，可检索，终态回填 answer）
--   agentx_monitor_trace         轮次（一轮 LLM 调用一行，瘦行）
-- 复用已有表（D3）：agentx_trace_payload（大字段明细）、agentx_monitor_metric、agentx_monitor_report。
-- 系统三表 agentx_conversation / agentx_session / agentx_trace 完全不动（D1，双轨写入 D5）。
-- 历史数据不回刷（D4）：监控中心自切换上线日起生效。
--
-- 应用启动时 MonitorTraceCollector 也会自动幂等建表，本脚本供 DBA 审核与提前执行，
-- 两者结构保持一致（UPSTREAM.md 冲突区约定）。
-- =====================================================================

-- ① 窗口表：列表页主表，聚合列由 Collector 在 open/closeSession 全量重算（自愈不漂移）
CREATE TABLE IF NOT EXISTS agentx_monitor_conversation (
  id                bigint PRIMARY KEY,
  conversation_id   varchar(100) NOT NULL UNIQUE,
  user_id           varchar(100),
  title             varchar(120),              -- 首问截断（列表展示）
  question_count    int  DEFAULT 0,
  total_rounds      int  DEFAULT 0,
  fail_rounds       int  DEFAULT 0,
  prompt_tokens     bigint DEFAULT 0,
  completion_tokens bigint DEFAULT 0,
  status            varchar(20),               -- 聚合态 running/error/interrupted/completed
  started_at        timestamp,
  last_active_at    timestamp,
  created_at        timestamp DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_amc_user   ON agentx_monitor_conversation (user_id, last_active_at DESC);
CREATE INDEX IF NOT EXISTS idx_amc_active ON agentx_monitor_conversation (last_active_at DESC);

-- ② 提问表：一次提问一行（检索入口；session_id 与系统表 agentx_conversation.session_id 1:1）
CREATE TABLE IF NOT EXISTS agentx_monitor_session (
  id              bigint PRIMARY KEY,
  conversation_id varchar(100) NOT NULL,
  session_id      varchar(100) NOT NULL UNIQUE,
  seq             int NOT NULL,                -- 窗口内第几问（前端展示 Q1/Q2）
  user_id         varchar(100),
  question        text NOT NULL,
  status          varchar(20) DEFAULT 'running', -- running/completed/error/interrupted
  answer          text,                          -- 终态回填最终回答（口径 = AnswerExtractor）
  answer_chars    int,                           -- 列表懒加载门控
  answer_trace_id bigint,                        -- 终答所在轮，点击直达其 OUTPUT
  rounds          int,
  fail_rounds     int,
  prompt_tokens   bigint,
  completion_tokens bigint,
  duration_ms     bigint,
  first_error     text,                          -- 首个失败轮错误（第 3 步直出）
  created_at      timestamp DEFAULT CURRENT_TIMESTAMP,
  completed_at    timestamp
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ams_session ON agentx_monitor_session (session_id);
CREATE INDEX IF NOT EXISTS idx_ams_conv    ON agentx_monitor_session (conversation_id, seq);
CREATE INDEX IF NOT EXISTS idx_ams_created ON agentx_monitor_session (created_at);
CREATE INDEX IF NOT EXISTS idx_ams_running ON agentx_monitor_session (created_at) WHERE status = 'running';
-- 阶段二再启用（业务 SQL 不用改）：
-- CREATE EXTENSION IF NOT EXISTS pg_trgm;
-- CREATE INDEX IF NOT EXISTS idx_ams_q_trgm ON agentx_monitor_session USING gin (question gin_trgm_ops);

-- ③ 轮次表：一轮 LLM 调用一行（瘦行；input/output/think 按 id 写 agentx_trace_payload）
CREATE TABLE IF NOT EXISTS agentx_monitor_trace (
  id              bigint PRIMARY KEY,            -- 雪花；同时写 agentx_trace_payload.trace_id（共存不冲突）
  conversation_id varchar(100) NOT NULL,         -- 冗余：窗口级查询不 JOIN
  session_id      varchar(100) NOT NULL,
  round           int NOT NULL,                  -- 提问内计数，从 1
  round_type      varchar(10),                   -- tool/text/error
  tool_names      text[],
  input_chars     int,
  prompt_tokens   bigint,
  completion_tokens bigint,
  duration_ms     bigint,
  think_duration_ms bigint,
  success         int DEFAULT 1,
  error_message   text,
  created_at      timestamp DEFAULT CURRENT_TIMESTAMP
);
-- ★ 核心教训：唯一键必须是"一次提问内一轮"（session_id + round），
--   绝不是 conversation_id + round（多轮会话第 2 问起必撞键 → trace 静默丢失，见方案 §0.2）
CREATE UNIQUE INDEX IF NOT EXISTS uk_amt_session_round ON agentx_monitor_trace (session_id, round);
CREATE INDEX IF NOT EXISTS idx_amt_conv    ON agentx_monitor_trace (conversation_id, created_at);
CREATE INDEX IF NOT EXISTS idx_amt_created ON agentx_monitor_trace (created_at);
CREATE INDEX IF NOT EXISTS idx_amt_failed  ON agentx_monitor_trace (session_id) WHERE success = 0;
