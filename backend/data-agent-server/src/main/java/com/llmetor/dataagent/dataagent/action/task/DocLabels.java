package com.llmetor.dataagent.dataagent.action.task;

/**
 * 文档模块状态字典（M-E1）：主后端散落在 Schema 注释/常量里的取值口径集中到这里，
 * 工具输出与卡片展示统一用中文标签，不向用户/LLM 暴露裸数字。
 *
 * <ul>
 *   <li>台账 doc_ledger.status：0草稿 1审批中 2已通过 3已驳回 4部分通过</li>
 *   <li>条目 doc_file_entry.audit_status：0草稿 1审批中 2已通过 3已驳回</li>
 *   <li>条目 doc_file_entry.not_applicable：-1待上传 0已取得 1空缺 2不涉及 3其他</li>
 *   <li>待办 approveStatus（任务变量）：1已通过 2已驳回 3已撤回 4部分通过</li>
 *   <li>审批轨迹 approveStatus（hi_task）：0待审核 1通过 2驳回</li>
 * </ul>
 */
public final class DocLabels {

    private DocLabels() {
    }

    /** 台账状态（0草稿 1审批中 2已通过 3已驳回 4部分通过） */
    public static String ledgerStatus(Integer v) {
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
    public static String auditStatus(Integer v) {
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
    public static String notApplicable(Integer v) {
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

    /** 待办任务 approveStatus（任务变量，可空） */
    public static String todoApproveStatus(Integer v) {
        if (v == null) {
            return "";
        }
        return switch (v) {
            case 1 -> "已通过";
            case 2 -> "已驳回";
            case 3 -> "已撤回";
            case 4 -> "部分通过";
            default -> "";
        };
    }

    /** 审批轨迹 approveStatus（hi_task 口径） */
    public static String traceApproveStatus(Integer v) {
        if (v == null) {
            return "待审核";
        }
        return switch (v) {
            case 0 -> "待审核";
            case 1 -> "通过";
            case 2 -> "驳回";
            default -> "未知(" + v + ")";
        };
    }
}
