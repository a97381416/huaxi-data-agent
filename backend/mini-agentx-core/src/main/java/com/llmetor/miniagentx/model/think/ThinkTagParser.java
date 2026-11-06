package com.llmetor.miniagentx.model.think;

/**
 * &lt;think&gt; 标签流式状态机解析器（ThinkingMode.THINK_TAG，MiniMax M3 路线）。
 *
 * <p>思考内容以 {@code <think>}（允许带属性变体，如 {@code <think lang="zh">}）嵌在 content 里。
 * chunk 逐段喂入 {@link #feed}，解析器在"正文/思考"两种模式间切换，分别吐出正文增量与思考增量；
 * 标签可能拆在两个 chunk 边界，尾部疑似标签前缀的部分暂存缓冲，等下个 chunk 到齐再判定。
 * 流结束时调 {@link #flush()}，残留缓冲按当前模式发出（不吞字）。
 *
 * <p>线程模型：单流单线程顺序 feed（ReAct 循环内由同一订阅线程驱动）。
 */
public class ThinkTagParser {

    private static final String OPEN_TAG = "<think";
    private static final String CLOSE_TAG = "</think";

    private boolean inThink = false;
    private final StringBuilder buf = new StringBuilder();

    /**
     * 一次 feed 的产出：正文增量与思考增量（均可为 null/空）。
     */
    public record Output(String textDelta, String thinkingDelta) {
    }

    /**
     * 喂入一个 chunk，返回解析出的增量。
     */
    public Output feed(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return new Output(null, null);
        }
        buf.append(chunk);
        StringBuilder textOut = new StringBuilder();
        StringBuilder thinkOut = new StringBuilder();

        boolean progress = true;
        while (progress) {
            progress = false;
            if (!inThink) {
                int idx = buf.indexOf(OPEN_TAG);
                if (idx >= 0) {
                    int gt = buf.indexOf(">", idx);
                    if (gt >= 0) {
                        // 完整开标签：<think> 或 <think attr="...">
                        textOut.append(buf, 0, idx);
                        buf.delete(0, gt + 1);
                        inThink = true;
                        progress = true;
                        continue;
                    }
                    // 有 "<think" 但 ">" 未到：暂存，等下个 chunk
                    textOut.append(buf, 0, idx);
                    buf.delete(0, idx);
                    break;
                }
                // 无完整开标签：尾部可能是残缺标签前缀，暂存最长匹配的后缀
                int hold = longestSuffixPrefix(buf, OPEN_TAG);
                int emitLen = buf.length() - hold;
                if (emitLen > 0) {
                    textOut.append(buf, 0, emitLen);
                    buf.delete(0, emitLen);
                }
                break;
            } else {
                int idx = buf.indexOf(CLOSE_TAG);
                if (idx >= 0) {
                    int gt = buf.indexOf(">", idx);
                    if (gt >= 0) {
                        thinkOut.append(buf, 0, idx);
                        buf.delete(0, gt + 1);
                        inThink = false;
                        progress = true;
                        continue;
                    }
                    // 有 "</think" 但 ">" 未到
                    thinkOut.append(buf, 0, idx);
                    buf.delete(0, idx);
                    break;
                }
                int hold = longestSuffixPrefix(buf, CLOSE_TAG);
                int emitLen = buf.length() - hold;
                if (emitLen > 0) {
                    thinkOut.append(buf, 0, emitLen);
                    buf.delete(0, emitLen);
                }
                break;
            }
        }

        return new Output(
                textOut.length() == 0 ? null : textOut.toString(),
                thinkOut.length() == 0 ? null : thinkOut.toString());
    }

    /**
     * 流结束：残留缓冲（含永远等不到的残缺标签前缀）按当前模式原样发出，不吞字。
     */
    public Output flush() {
        if (buf.length() == 0) {
            return new Output(null, null);
        }
        String rest = buf.toString();
        buf.setLength(0);
        return inThink
                ? new Output(null, rest)
                : new Output(rest, null);
    }

    public boolean isInThink() {
        return inThink;
    }

    /**
     * 计算 buf 的最长后缀，使其是 tag 的（真）前缀。
     * 例：buf="abc&lt;th"，tag="&lt;think" → 3。
     */
    private static int longestSuffixPrefix(StringBuilder buf, String tag) {
        int max = Math.min(buf.length(), tag.length());
        for (int k = max; k >= 1; k--) {
            boolean match = true;
            int base = buf.length() - k;
            for (int i = 0; i < k; i++) {
                if (buf.charAt(base + i) != tag.charAt(i)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return k;
            }
        }
        return 0;
    }
}
