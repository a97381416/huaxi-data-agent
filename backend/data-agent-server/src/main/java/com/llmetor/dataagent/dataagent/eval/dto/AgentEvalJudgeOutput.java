package com.llmetor.dataagent.dataagent.eval.dto;

import lombok.Data;

/**
 * 裁判结构化输出（BeanOutputConverter 目标，只出四维档位分 + 判语）。
 *
 * <p>加权总分与 pass 由程序计算，裁判无权决定；四维分缺失视为解析失败走重试。
 */
@Data
public class AgentEvalJudgeOutput {

    /** 正确性 0-5：数值和结论对不对，有没有编造 */
    private Integer correctness;
    /** 完整性 0-5：该答的要点覆盖了没 */
    private Integer completeness;
    /** 相关性 0-5：有没有跑题、乱补充无关分析 */
    private Integer relevance;
    /** 逻辑性 0-5：结论组织、推理是否清晰 */
    private Integer logic;
    /** 判语：扣分原因与比对依据 */
    private String reason;
}
