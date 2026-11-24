-- M-SA3：多 Agent 父子全链路 Trace 树与监控口径隔离（全部幂等，兼容单 Agent 回滚）

-- 1. agentx_trace：唯一索引从 (conversation_id, round) 迁移为 (session_id, round)，防主子同处 round=1 撞键
DROP INDEX IF EXISTS uk_trace_conv_round;
CREATE UNIQUE INDEX IF NOT EXISTS uk_trace_session_round ON agentx_trace (session_id, round);
ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS agent_code varchar(50) DEFAULT 'router';
ALTER TABLE agentx_trace ADD COLUMN IF NOT EXISTS parent_session_id varchar(100) DEFAULT NULL;

-- 2. agentx_monitor_session：记录子会话归属 agent_code、父 session_id 及父 tool_call_id
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS agent_code varchar(50) DEFAULT 'router';
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS parent_session_id varchar(100) DEFAULT NULL;
ALTER TABLE agentx_monitor_session ADD COLUMN IF NOT EXISTS parent_tool_call_id varchar(100) DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_ams_parent ON agentx_monitor_session (parent_session_id);

-- 3. agentx_monitor_trace：记录轮次归属 agent_code 与父 session_id
ALTER TABLE agentx_monitor_trace ADD COLUMN IF NOT EXISTS agent_code varchar(50) DEFAULT 'router';
ALTER TABLE agentx_monitor_trace ADD COLUMN IF NOT EXISTS parent_session_id varchar(100) DEFAULT NULL;
