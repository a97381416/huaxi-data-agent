-- =====================================================================
-- 找到异常 · 异常检测规则引擎（Step 3）· 数据层（T1）
-- 方案：docs/trace追溯设计/03-找到异常-异常检测规则引擎.md §4
-- 在 data-agent 业务库（PG，schema=public）执行；幂等可重复执行。
--
-- 新增：
--   agentx_monitor_risk          风险表（一次命中一行：规则ID+级别+定位轮+证据）
--   agentx_monitor_detect_rule   规则参数配置表（阈值/开关 web 管理，读库优先）
-- 加列：
--   agentx_monitor_session       detect_status / risk_total / risk_high /
--                                first_risk / detector_version（列表直出 + 兜底扫描）
--
-- 应用启动时 AnomalyDetectService/RiskStore 也会自动幂等建表 + seed 规则行，
-- 本脚本供 DBA 审核与提前执行，两者结构保持一致（UPSTREAM.md 冲突区约定）。
-- =====================================================================

-- ① 风险表：一次命中一行（检测幂等 = DELETE 该 session 旧风险 → INSERT 命中行）
CREATE TABLE IF NOT EXISTS agentx_monitor_risk (
  id              bigint PRIMARY KEY,
  conversation_id varchar(100) NOT NULL,
  session_id      varchar(100) NOT NULL,
  rule_id         varchar(20)  NOT NULL,   -- REACT-003 / TOOL-001 / ANSWER-001 …
  severity        varchar(10)  NOT NULL,   -- high / medium / low
  round           int,                     -- 主定位轮（提问内 round，深链用）
  trace_id        bigint,                  -- 主定位轮 trace 行 id
  rounds_hit      varchar(100),            -- 跨轮规则涉及轮清单，如 "5,6,7"
  title           varchar(200) NOT NULL,   -- 规则名
  detail          text,                    -- 证据摘要（一句人话，列表直出）
  evidence        jsonb,                   -- 结构化证据：args哈希/观察摘录/比对数字
  created_at      timestamp DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_amr_session ON agentx_monitor_risk (session_id);
CREATE INDEX IF NOT EXISTS idx_amr_conv    ON agentx_monitor_risk (conversation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_amr_rule    ON agentx_monitor_risk (rule_id, severity);

-- ② 提问表加列（列表直出 + SweepJob 兜底扫描；列默认值只对新行生效）
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS detect_status    varchar(20);  -- pending / done / failed（终态未检测=待兜底）
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS risk_total       int  DEFAULT 0;
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS risk_high        int  DEFAULT 0;
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS first_risk       text;         -- 首个 high 风险一句话（同 first_error 交互）
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS detector_version varchar(10);  -- 引擎版本，规则升级可按此重刷（V1 只留字段）
CREATE INDEX IF NOT EXISTS idx_ams_undetected ON agentx_monitor_session (created_at)
  WHERE detect_status IS NULL OR detect_status <> 'done';

-- ③ 规则参数配置表（D7 轻量 web 管理：PUT /monitor/detect/rules/{ruleId} 即时生效免重启；
--    首次启动代码 seed 全量规则行，表缺行/解析失败回退代码默认值）
CREATE TABLE IF NOT EXISTS agentx_monitor_detect_rule (
  rule_id     varchar(20) PRIMARY KEY,   -- REACT-003 / TOOL-001 …
  enabled     boolean NOT NULL DEFAULT true,
  params      jsonb NOT NULL DEFAULT '{}',  -- 各规则阈值：{"maxRounds":15} / {"repeatCalls":3} …
  updated_by  varchar(100),
  updated_at  timestamp DEFAULT CURRENT_TIMESTAMP
);
