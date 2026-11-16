package com.llmetor.dataagent.dataagent.action.fill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FillContextSupport 静态方法单测：resolveNode 节点解析——
 * ID/编码/名称精确 → 名称包含唯一命中 → 歧义/未命中抛候选清单（宁可响亮歧义，绝不静默错节点）。
 */
class FillContextSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private List<ObjectNode> nodes() {
        List<ObjectNode> nodes = new ArrayList<>();
        nodes.add(node("1880000000000000001", "NA", "立项文件节点", 2));
        nodes.add(node("1880000000000000002", "NB", "施工许可文件节点", 3));
        nodes.add(node("1880000000000000003", "NC", "竣工验收文件节点", 1));
        return nodes;
    }

    private ObjectNode node(String id, String code, String name, int catCount) {
        ObjectNode n = mapper.createObjectNode();
        n.put("nodeId", id);
        n.put("nodeCode", code);
        n.put("nodeName", name);
        n.put("required", catCount > 2);
        ArrayNode arr = n.putArray("presetCategories");
        for (int i = 0; i < catCount; i++) {
            ObjectNode cat = arr.addObject();
            cat.put("code", "C" + i);
            cat.put("name", "类别" + i);
            cat.put("summary", "");
        }
        return n;
    }

    @Test
    @DisplayName("节点 ID 精确命中")
    void byIdExact() {
        ObjectNode n = FillContextSupport.resolveNode(nodes(), "1880000000000000002");
        assertThat(n.path("nodeName").asText()).isEqualTo("施工许可文件节点");
    }

    @Test
    @DisplayName("节点编码精确命中")
    void byCodeExact() {
        ObjectNode n = FillContextSupport.resolveNode(nodes(), "NC");
        assertThat(n.path("nodeId").asText()).isEqualTo("1880000000000000003");
    }

    @Test
    @DisplayName("节点名称精确命中")
    void byNameExact() {
        ObjectNode n = FillContextSupport.resolveNode(nodes(), "立项文件节点");
        assertThat(n.path("nodeId").asText()).isEqualTo("1880000000000000001");
    }

    @Test
    @DisplayName("名称包含唯一命中")
    void byNameContainsUnique() {
        ObjectNode n = FillContextSupport.resolveNode(nodes(), "竣工验收");
        assertThat(n.path("nodeId").asText()).isEqualTo("1880000000000000003");
    }

    @Test
    @DisplayName("名称包含多命中 → 抛候选清单（不静默错节点）")
    void ambiguousThrowsWithCandidates() {
        assertThatThrownBy(() -> FillContextSupport.resolveNode(nodes(), "文件节点"))
                .isInstanceOf(FillContextSupport.TaskFlowException.class)
                .hasMessageContaining("唯一命中")
                .hasMessageContaining("立项文件节点")
                .hasMessageContaining("竣工验收文件节点");
    }

    @Test
    @DisplayName("未命中 → 抛节点清单")
    void notFoundThrowsWithNodeList() {
        assertThatThrownBy(() -> FillContextSupport.resolveNode(nodes(), "不存在的节点"))
                .isInstanceOf(FillContextSupport.TaskFlowException.class)
                .hasMessageContaining("未找到")
                .hasMessageContaining("施工许可文件节点");
    }

    @Test
    @DisplayName("空参 → 抛缺参话术")
    void blankThrows() {
        assertThatThrownBy(() -> FillContextSupport.resolveNode(nodes(), " "))
                .isInstanceOf(FillContextSupport.TaskFlowException.class)
                .hasMessageContaining("缺少填报节点标识");
    }
}
