package com.llmetor.dataagent.dataagent.action.fill;

/**
 * 资料填报状态字典（M-E2）：口径与 M-E1 {@code DocLabels} 一致（doc 模块散落在
 * Schema 注释里的取值），工具输出与卡片统一中文标签，不向用户/LLM 暴露裸数字。
 *
 * <ul>
 *   <li>台账 doc_ledger.status：0草稿 1审批中 2已通过 3已驳回 4部分通过</li>
 *   <li>条目 doc_file_entry.audit_status：0草稿 1审批中 2已通过 3已驳回</li>
 *   <li>条目 doc_file_entry.not_applicable：-1待上传 0已取得 1空缺 2不涉及 3其他</li>
 * </ul>
 */
final class FillLabels {

    private FillLabels() {
    }

    /** 台账状态（0草稿 1审批中 2已通过 3已驳回 4部分通过） */
    static String ledgerStatus(Integer v) {
        if (v == null) {
            return "未知";
        }
        return switch (v) {
            case 0 -> "草稿";
            case 1 -> "审批中";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            case 4 -> "部分通过";
            default -> "未知(" + v + ")";
        };
    }

    /** 条目审核状态（0草稿 1审批中 2已通过 3已驳回） */
    static String auditStatus(Integer v) {
        if (v == null) {
            return "—";
        }
        return switch (v) {
            case 0 -> "草稿";
            case 1 -> "审批中";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            default -> "未知(" + v + ")";
        };
    }

    /** 取得状态 not_applicable（-1待上传 0已取得 1空缺 2不涉及 3其他） */
    static String notApplicable(Integer v) {
        if (v == null) {
            return "已取得";
        }
        return switch (v) {
            case -1 -> "待上传";
            case 0 -> "已取得";
            case 1 -> "空缺";
            case 2 -> "不涉及";
            case 3 -> "其他";
            default -> "未知(" + v + ")";
        };
    }

    /** patch 字段名 → 用户可读字段名（diff 表与暂存回报用） */
    static String fieldLabel(String field) {
        return switch (field == null ? "" : field) {
            case "notApplicable" -> "文件状态";
            case "summary" -> "文件摘要";
            case "description" -> "备注";
            case "fileUrl", "fileName" -> "文件";
            case "attachmentUrl", "attachmentName" -> "附件";
            case "provider" -> "提供人";
            case "providerTime" -> "提供时间";
            case "isConfidential" -> "是否涉密";
            case "visibleRoleIds" -> "可见角色";
            default -> field == null ? "" : field;
        };
    }
}
