-- =====================================================================
-- 规则管理 · 备注字段（remark）
-- agentx_monitor_detect_rule 加备注列：规则管理页可对单条规则写备注说明
-- （为什么调阈值/关规则，团队留痕）；remark 仅人工维护，不参与检测判定。
-- 在 data-agent 业务库（PG，schema=public）执行；幂等可重复执行。
-- =====================================================================

ALTER TABLE agentx_monitor_detect_rule ADD COLUMN IF NOT EXISTS remark text;
