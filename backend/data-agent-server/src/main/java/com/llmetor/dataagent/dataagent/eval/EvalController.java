package com.llmetor.dataagent.dataagent.eval;

import com.llmetor.dataagent.common.R;
import com.llmetor.dataagent.dataagent.eval.dto.AgentDiagnoseRequest;
import com.llmetor.dataagent.dataagent.eval.dto.AgentDiagnoseResponse;
import com.llmetor.dataagent.dataagent.eval.dto.AgentEvalJudgeRequest;
import com.llmetor.dataagent.dataagent.eval.dto.AgentEvalJudgeResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 评测体系接口（M8）：裁判判分 + 错题诊断 + 数据集校验 + 报告查看。
 *
 * <p>judge/diagnose <b>免鉴权</b>（SaTokenConfig notMatch 单端点放行，对齐原版"本地 runner
 * 专用"）：供 {@code scripts/agent/} 下 Python runner 直连调用，响应不包 R&lt;T&gt; 壳
 * （runner 直接读顶层 success 字段）。生产部署应在网关层禁用这两个端点。
 *
 * <p>dataset/check 与 report/* 走正常登录鉴权（TestHub/报告页用）。
 */
@Slf4j
@RestController
@RequestMapping("/agent/eval")
@RequiredArgsConstructor
public class EvalController {

    private final AgentEvalJudgeService judgeService;
    private final AgentDiagnoseService diagnoseService;
    private final EvalDatasetCheckService datasetCheckService;
    private final EvalReportService reportService;

    /** 裁判判分：四维 0-5 档位分 + 程序算的加权总分与通过判定（免鉴权） */
    @PostMapping("/judge")
    public AgentEvalJudgeResponse judge(@RequestBody AgentEvalJudgeRequest req) {
        return judgeService.judge(req);
    }

    /** 错题诊断：诊断 Agent 沿 trace 找第一次出错的轮次并归因（免鉴权） */
    @PostMapping("/diagnose")
    public AgentDiagnoseResponse diagnose(@RequestBody AgentDiagnoseRequest req) {
        return diagnoseService.diagnose(req);
    }

    /** 数据集校验：题量/难度配比/三池交错/多轮/run_role/锚点 + gold_sql 全量对库复跑（M8-A 组自动判分） */
    @GetMapping("/dataset/check")
    public R<Map<String, Object>> datasetCheck() {
        return R.ok(datasetCheckService.check());
    }

    /** 评测报告清单（基线报告 + 错题诊断报告） */
    @GetMapping("/report")
    public R<List<Map<String, Object>>> reportList() {
        try {
            return R.ok(reportService.list());
        } catch (IOException e) {
            log.warn("[agent-eval] 报告清单读取失败: {}", e.getMessage());
            return R.fail("报告目录不可读: " + e.getMessage());
        }
    }

    /** 报告原文（markdown 文本） */
    @GetMapping("/report/content")
    public R<String> reportContent(@RequestParam String name) {
        try {
            return R.ok(reportService.content(name));
        } catch (IllegalArgumentException e) {
            return R.notFound(e.getMessage());
        } catch (IOException e) {
            log.warn("[agent-eval] 报告读取失败: {}", e.getMessage());
            return R.fail("报告不可读: " + e.getMessage());
        }
    }
}
