package com.llmetor.dataagent.dataagent.mcp;

import com.llmetor.dataagent.common.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * MCP 服务管理 API（M6 补充，仅 admin——/api/mcp/** 拦截器在 SaTokenConfig，规则先行）。
 *
 * <p>语义：增删改/启停即时热生效（下一轮对话可用）；连接失败不阻断保存
 * （status=ERROR 标红，可稍后手动重连）；凭据脱敏回传，含 *** 回传值视为未修改。
 */
@RestController
@RequestMapping("/api/mcp/servers")
@RequiredArgsConstructor
@Slf4j
public class McpController {

    private final McpServerManager mcpServerManager;

    /** 列出全部 MCP 服务（含连通状态/工具数，凭据脱敏） */
    @GetMapping
    public R<List<McpServerInfo>> list() {
        return R.ok(mcpServerManager.list());
    }

    /** 新建服务（校验失败/重名 → R.fail；启用则立即连通，失败仅落 ERROR 状态） */
    @PostMapping
    public R<McpServerInfo> create(@RequestBody McpServerUpsertRequest request) {
        try {
            return R.ok(mcpServerManager.create(request));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[mcp] 新建失败: {}", e.getMessage(), e);
            return R.fail("新建失败: " + e.getMessage());
        }
    }

    /** 编辑服务（name 不可改；凭据含 *** 视为未修改；保存后热重建客户端） */
    @PutMapping("/{id}")
    public R<McpServerInfo> update(@PathVariable Long id, @RequestBody McpServerUpsertRequest request) {
        try {
            return R.ok(mcpServerManager.update(id, request));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[mcp] 编辑失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("编辑失败: " + e.getMessage());
        }
    }

    /** 删除服务（关客户端 + 删行；yml 种子行也可删，删即彻底移除） */
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        try {
            return R.ok(mcpServerManager.delete(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[mcp] 删除失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("删除失败: " + e.getMessage());
        }
    }

    /** 启停（启用 → 立即连通；停用 → 关客户端，工具即从 Agent 清单消失） */
    @PutMapping("/{id}/toggle")
    public R<McpServerInfo> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        try {
            return R.ok(mcpServerManager.toggle(id, enabled));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[mcp] 启停失败: id={}, enabled={}: {}", id, enabled, e.getMessage(), e);
            return R.fail("启停失败: " + e.getMessage());
        }
    }

    /** 连接测试（不落库；编辑场景 id 非空时凭据缺省回退库中值；失败也返回结构化结果不抛错） */
    @PostMapping("/test")
    public R<McpTestResult> test(@RequestParam(required = false) Long id,
                                 @RequestBody McpServerUpsertRequest request) {
        return R.ok(mcpServerManager.test(id, request));
    }

    /** 工具预览（读运行时注册表；未连接返回空清单） */
    @GetMapping("/{id}/tools")
    public R<List<McpToolInfo>> tools(@PathVariable Long id) {
        try {
            return R.ok(mcpServerManager.listTools(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[mcp] 工具预览失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("工具预览失败: " + e.getMessage());
        }
    }

    /** 手动重连（关旧客户端 → 按当前配置重连并刷新状态） */
    @PostMapping("/{id}/sync")
    public R<McpServerInfo> sync(@PathVariable Long id) {
        try {
            return R.ok(mcpServerManager.reconnect(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[mcp] 重连失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("重连失败: " + e.getMessage());
        }
    }
}
