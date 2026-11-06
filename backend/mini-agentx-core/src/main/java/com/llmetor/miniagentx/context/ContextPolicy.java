package com.llmetor.miniagentx.context;

/**
 * 上下文压缩策略配置（默认值对齐原框架 ContextPolicy，docs/02 §4.1）。
 *
 * <ul>
 *   <li>lastKeep=50：保护区条数——最近 50 条消息不被任何策略压缩</li>
 *   <li>msgThreshold=100：消息数触发阈值（≥100 条进策略链）</li>
 *   <li>tokenThreshold=90000：token 触发阈值（≈128K×0.7）</li>
 *   <li>minConsecutiveToolMessages=6：L1 连续工具消息段的最小条数</li>
 *   <li>minCompressionTokens=5000：L1/L4 段的最小 token 数（不值得压的小段直接跳过）</li>
 *   <li>largePayloadTokens=2000：L2 单条大消息判定阈值</li>
 *   <li>offloadPreviewChars=200：offload 替换文本里保留的原文预览长度</li>
 *   <li>maxLlmCompressionCount=3：单次压缩最多 LLM 调用次数（= L4 最多摘要轮数）</li>
 *   <li>currentRoundRatio=0.3：L6 当前轮目标压缩比（预留，M7 未启用 L5/L6）</li>
 * </ul>
 *
 * <p>不可变对象；builder 便于业务按 yml 覆盖个别值。
 */
public class ContextPolicy {

    private final int lastKeep;
    private final int msgThreshold;
    private final int tokenThreshold;
    private final int minConsecutiveToolMessages;
    private final int minCompressionTokens;
    private final int largePayloadTokens;
    private final int offloadPreviewChars;
    private final int maxLlmCompressionCount;
    private final double currentRoundRatio;

    private ContextPolicy(Builder b) {
        this.lastKeep = b.lastKeep;
        this.msgThreshold = b.msgThreshold;
        this.tokenThreshold = b.tokenThreshold;
        this.minConsecutiveToolMessages = b.minConsecutiveToolMessages;
        this.minCompressionTokens = b.minCompressionTokens;
        this.largePayloadTokens = b.largePayloadTokens;
        this.offloadPreviewChars = b.offloadPreviewChars;
        this.maxLlmCompressionCount = b.maxLlmCompressionCount;
        this.currentRoundRatio = b.currentRoundRatio;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 文档默认值（02 §4.1） */
    public static ContextPolicy defaults() {
        return builder().build();
    }

    public int getLastKeep() {
        return lastKeep;
    }

    public int getMsgThreshold() {
        return msgThreshold;
    }

    public int getTokenThreshold() {
        return tokenThreshold;
    }

    public int getMinConsecutiveToolMessages() {
        return minConsecutiveToolMessages;
    }

    public int getMinCompressionTokens() {
        return minCompressionTokens;
    }

    public int getLargePayloadTokens() {
        return largePayloadTokens;
    }

    public int getOffloadPreviewChars() {
        return offloadPreviewChars;
    }

    public int getMaxLlmCompressionCount() {
        return maxLlmCompressionCount;
    }

    public double getCurrentRoundRatio() {
        return currentRoundRatio;
    }

    public static class Builder {
        private int lastKeep = 50;
        private int msgThreshold = 100;
        private int tokenThreshold = 90_000;
        private int minConsecutiveToolMessages = 6;
        private int minCompressionTokens = 5_000;
        private int largePayloadTokens = 2_000;
        private int offloadPreviewChars = 200;
        private int maxLlmCompressionCount = 3;
        private double currentRoundRatio = 0.3;

        public Builder lastKeep(int v) {
            this.lastKeep = v;
            return this;
        }

        public Builder msgThreshold(int v) {
            this.msgThreshold = v;
            return this;
        }

        public Builder tokenThreshold(int v) {
            this.tokenThreshold = v;
            return this;
        }

        public Builder minConsecutiveToolMessages(int v) {
            this.minConsecutiveToolMessages = v;
            return this;
        }

        public Builder minCompressionTokens(int v) {
            this.minCompressionTokens = v;
            return this;
        }

        public Builder largePayloadTokens(int v) {
            this.largePayloadTokens = v;
            return this;
        }

        public Builder offloadPreviewChars(int v) {
            this.offloadPreviewChars = v;
            return this;
        }

        public Builder maxLlmCompressionCount(int v) {
            this.maxLlmCompressionCount = v;
            return this;
        }

        public Builder currentRoundRatio(double v) {
            this.currentRoundRatio = v;
            return this;
        }

        public ContextPolicy build() {
            return new ContextPolicy(this);
        }
    }
}
