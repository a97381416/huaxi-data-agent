package com.llmetor.dataagent.dataagent.model;

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
 * 对话模型管理 API（仅 admin——鉴权口径同 /api/mcp/**：HuaxiAuthFilter 登录校验，
 * admin 门禁由前端路由守卫负责）。
 *
 * <p>语义：增删改/启停/设默认即时热生效（下一轮对话可用）；删除/停用默认模型自动移交默认；
 * 凭据脱敏回传，含 *** 回传值视为未修改。
 */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
@Slf4j
public class ModelController {

    private final ChatModelManager chatModelManager;

    /** 列出全部对话模型（凭据脱敏，含默认标记/最近测试结果） */
    @GetMapping
    public R<List<ChatModelInfo>> list() {
        return R.ok(chatModelManager.list());
    }

    /** 新建模型（校验失败/code 重复 → R.fail；首行或显式 isDefault=true 即成为默认） */
    @PostMapping
    public R<ChatModelInfo> create(@RequestBody ChatModelUpsertRequest request) {
        try {
            return R.ok(chatModelManager.create(request));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[model] 新建失败: {}", e.getMessage(), e);
            return R.fail("新建失败: " + e.getMessage());
        }
    }

    /** 编辑模型（code 不可改；凭据含 *** 视为未修改；保存后热生效） */
    @PutMapping("/{id}")
    public R<ChatModelInfo> update(@PathVariable Long id, @RequestBody ChatModelUpsertRequest request) {
        try {
            return R.ok(chatModelManager.update(id, request));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[model] 编辑失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("编辑失败: " + e.getMessage());
        }
    }

    /** 删除模型（删的是默认行则自动移交 sort 最小的启用行） */
    @DeleteMapping("/{id}")
    public R<Boolean> delete(@PathVariable Long id) {
        try {
            return R.ok(chatModelManager.delete(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[model] 删除失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("删除失败: " + e.getMessage());
        }
    }

    /** 启停（停用默认行自动移交默认；停用后即从对话页选择器消失） */
    @PutMapping("/{id}/toggle")
    public R<ChatModelInfo> toggle(@PathVariable Long id, @RequestParam boolean enabled) {
        try {
            return R.ok(chatModelManager.toggle(id, enabled));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[model] 启停失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("启停失败: " + e.getMessage());
        }
    }

    /** 设为默认模型（须已启用；全局唯一默认） */
    @PutMapping("/{id}/default")
    public R<ChatModelInfo> setDefault(@PathVariable Long id) {
        try {
            return R.ok(chatModelManager.setDefault(id));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[model] 设默认失败: id={}: {}", id, e.getMessage(), e);
            return R.fail("设默认失败: " + e.getMessage());
        }
    }

    /**
     * 连通测试（不落缓存）：编辑场景传 id，后端凭据缺省回退库中值；
     * 永远返回结构化结果 {ok, reply, elapsedMs, error}，不抛错。
     */
    @PostMapping("/test")
    public R<ChatModelTestResult> test(@RequestBody ChatModelUpsertRequest request,
                                       @RequestParam(required = false) Long id) {
        try {
            return R.ok(chatModelManager.test(id, request));
        } catch (IllegalArgumentException e) {
            return R.fail(e.getMessage());
        } catch (Exception e) {
            log.error("[model] 连通测试失败: {}", e.getMessage(), e);
            return R.fail("连通测试失败: " + e.getMessage());
        }
    }
}
