package com.llmetor.dataagent.dataagent.eval.bird;

import com.llmetor.dataagent.dataagent.eval.bird.dto.BirdEvalRequest;
import com.llmetor.dataagent.dataagent.eval.bird.dto.BirdEvalResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BIRD Dev 本地评测入口（M9 自原版 dodo-agentx 移植）。
 * <p>
 * 仅本地 Python runner 调用（SaToken 已放行，见 SaTokenConfig），不走业务鉴权。
 * 生产环境请勿暴露此接口。
 */
@Slf4j
@RestController
@RequestMapping("/bird/eval")
@RequiredArgsConstructor
public class BirdEvalController {

    private final BirdEvalService birdEvalService;

    /**
     * 单题评测（agent 模式）
     */
    @PostMapping("/question")
    public BirdEvalResponse question(@RequestBody BirdEvalRequest request) {
        log.info("[bird-eval] /bird/eval/question | questionId={} dbId={}",
                request.questionId(), request.dbId());
        return birdEvalService.evalQuestion(request);
    }

    /**
     * 单题无工具基线：直接 ChatModel 生成 SQL，用于对比 ReactAgent 增益。
     */
    @PostMapping("/baseline")
    public BirdEvalResponse baseline(@RequestBody BirdEvalRequest request) {
        log.info("[bird-eval] /bird/eval/baseline | questionId={} dbId={}",
                request.questionId(), request.dbId());
        return birdEvalService.evalBaseline(request);
    }
}
