-- =====================================================================
-- Trace 跟踪与监控模块 · M1 数据层改造（T1.1 + T1.2）
-- 在 data-agent 业务库（PG，schema=public）执行；幂等可重复执行。
--
-- T1.1 新增 agentx_trace_payload 明细表（大字段出瘦表，一对一 trace_id）
-- T1.2 agentx_trace 瘦表化：加分析列 round_type / tool_names / input_chars
--      + 瘦表专用索引（唯一索引防重、时间索引、失败轮部分索引）
--
-- 执行顺序：V01（本脚本）→ 部署双写代码（T1.3）→ V02 历史回刷（T1.4）
--          → 应用侧切换读 payload（T1.5）→ 稳定一周后 V05 回收（T1.6）
--
-- 应用启动时 TraceManager 也会自动幂等建表/加列，本脚本供 DBA 审核与提前执行，
-- 两者结构保持一致（UPSTREAM.md 冲突区约定）。
-- =====================================================================

-- ── T1.1 明细表：agentx_trace 的 input/output/think 大字段搬到这里 ──
CREATE TABLE IF NOT EXISTS agentx_trace_payload (
  trace_id bigint PRIMARY KEY,          -- = agentx_trace.id, 一对一
  input_data text,
  output_data text,
  think text,
  created_at timestamp NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_payload_created ON agentx_trace_payload (created_at);

-- ── T1.2 瘦表加列（写入时顺手提取，避免运行时解析大 JSON）──
ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS round_type varchar(10);   -- tool/text/error
ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS tool_names text[];        -- 本轮调用的工具名
ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS input_chars int;          -- 明细体积（懒加载摘要用）

-- 瘦表专用索引
-- 注意：uk_trace_conv_round 若存量数据存在 (conversation_id, round) 重复会创建失败——
-- 双写设计下一会话一轮仅一行，理论无重复；如失败请先跑去重排查再重试本脚本。
CREATE UNIQUE INDEX IF NOT EXISTS uk_trace_conv_round ON agentx_trace (conversation_id, round);
CREATE INDEX IF NOT EXISTS idx_trace_created ON agentx_trace (created_at);
-- 失败轮部分索引（监控"失败会话 TOP"高频查询）
CREATE INDEX IF NOT EXISTS idx_trace_failed ON agentx_trace (conversation_id) WHERE success = 0;
