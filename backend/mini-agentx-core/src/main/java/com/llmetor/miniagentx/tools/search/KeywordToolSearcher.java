package com.llmetor.miniagentx.tools.search;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * KEYWORD 模式打分器（docs/02 §7）：Jieba 分词 + 加权匹配。
 *
 * <p>打分规则（query 先分词）：
 * <ul>
 *   <li>工具名与整个 query 精确相等：+100</li>
 *   <li>query token 命中工具名分词：+50；未分中但工具名包含该 token：+20</li>
 *   <li>query token 命中描述分词：+10</li>
 * </ul>
 *
 * <p>catalog 条目注册时预分词一次、跨请求共享；query 每次现算。
 */
public final class KeywordToolSearcher {

    /** 目录条目：工具名 + 描述 + 预分词集合（注册时构建，只读共享） */
    public record CatalogEntry(String name, String description, Set<String> nameTokens, Set<String> descTokens) {

        public static CatalogEntry of(String name, String description) {
            return new CatalogEntry(name, description == null ? "" : description,
                    new LinkedHashSet<>(tokenize(name)),
                    new LinkedHashSet<>(tokenize(description)));
        }
    }

    /** 命中项：工具名 + 得分 */
    public record SearchHit(String name, double score) {
    }

    private static final JiebaSegmenter SEGMENTER = new JiebaSegmenter();

    private KeywordToolSearcher() {
    }

    /** 打分：返回总得分（0 = 无任何匹配） */
    public static double score(CatalogEntry entry, String rawQuery, List<String> queryTokens) {
        String name = entry.name().toLowerCase(Locale.ROOT);
        double s = 0;
        if (rawQuery != null && name.equals(rawQuery.trim().toLowerCase(Locale.ROOT))) {
            s += 100;
        }
        for (String token : queryTokens) {
            if (entry.nameTokens().contains(token)) {
                s += 50;
            } else if (name.contains(token)) {
                s += 20;
            }
            if (entry.descTokens().contains(token)) {
                s += 10;
            }
        }
        return s;
    }

    /** Jieba INDEX 模式分词（搜索向：粗细粒度并存），小写化、去纯标点 */
    public static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        for (com.huaban.analysis.jieba.SegToken token : SEGMENTER.process(text, JiebaSegmenter.SegMode.INDEX)) {
            String w = token.word == null ? "" : token.word.trim().toLowerCase(Locale.ROOT);
            if (w.isEmpty() || isNoise(w)) {
                continue;
            }
            tokens.add(w);
        }
        return tokens;
    }

    /** 纯标点/符号不入索引（保留单字——中文单字词如"查/表"有意义） */
    private static boolean isNoise(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }
}
