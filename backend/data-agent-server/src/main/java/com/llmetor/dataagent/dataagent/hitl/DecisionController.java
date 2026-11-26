package com.llmetor.dataagent.dataagent.hitl;

import com.llmetor.dataagent.auth.HuaxiAuthContext;
import com.llmetor.dataagent.auth.dto.LoginUserVO;
import com.llmetor.dataagent.auth.service.HuaxiAuthService;
import com.llmetor.dataagent.common.R;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.DecideRequest;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.DecideResponse;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.HitlCardPayload;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 一期 HITL 决策单与授权管理接口。
 *
 * <p>安全红线：
 * <ul>
 *   <li>绝不信任前端传入的 userId，强制从 {@link HuaxiAuthContext} 提取真人登录态；</li>
 *   <li>严禁将本接口注册为 LLM 工具。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/hitl")
@RequiredArgsConstructor
public class DecisionController {

    private final DecisionService decisionService;
    private final HuaxiAuthService authService;

    /**
     * 人工签字审批决策单（批准 / 拒绝 / 全部拒绝 / 本会话同类放行）。
     * 命中在线等待线程返回 HTTP 202 (mode=INLINE_RESUMED)；未命中返回 HTTP 200 (mode=NEED_RESUME)。
     */
    @PostMapping("/decisions/{id}/decide")
    public ResponseEntity<R<DecideResponse>> decide(@PathVariable("id") String decisionId,
                                                    @RequestBody DecideRequest request,
                                                    HttpServletRequest httpRequest) {
        Long userId = requireUserId();
        String userName = resolveUserName(userId);
        String clientIp = resolveClientIp(httpRequest);
        DecideResponse resp = decisionService.decide(decisionId, userId, userName, clientIp, request);
        if (DecisionModels.MODE_INLINE_RESUMED.equals(resp.mode())) {
            return ResponseEntity.accepted().body(R.ok(resp));
        }
        return ResponseEntity.ok(R.ok(resp));
    }

    /**
     * 查询当前用户待处理决策单（支持 ?conversationId=xxx 过滤或全局汇聚）。
     */
    @GetMapping("/decisions/pending")
    public R<List<HitlCardPayload>> listPending(
            @RequestParam(value = "conversationId", required = false) String conversationId,
            @RequestParam(value = "mine", required = false, defaultValue = "true") boolean mine) {
        Long userId = requireUserId();
        return R.ok(decisionService.listPending(userId, conversationId));
    }

    /**
     * 查询指定会话的全部决策单（含历史已决留痕与未决单据，用于刷新恢复与只读回显）。
     */
    @GetMapping("/decisions/conversation")
    public R<List<HitlCardPayload>> listByConversation(
            @RequestParam("conversationId") String conversationId) {
        Long userId = requireUserId();
        return R.ok(decisionService.listByConversation(userId, conversationId));
    }

    /**
     * 一键撤销会话级同类授权。
     */
    @PostMapping("/grants/{grantId}/revoke")
    public R<Map<String, Object>> revokeGrant(@PathVariable("grantId") String grantId) {
        Long userId = requireUserId();
        boolean revoked = decisionService.revokeGrant(grantId, userId);
        return R.ok(Map.of("grantId", grantId, "revoked", revoked));
    }

    private Long requireUserId() {
        Long userId = HuaxiAuthContext.getCurrentUserIdOrNull();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已过期");
        }
        return userId;
    }

    private String resolveUserName(long userId) {
        try {
            LoginUserVO user = authService.buildLoginUser(userId, null);
            return StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getUsername();
        } catch (Exception e) {
            return "用户#" + userId;
        }
    }

    private String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
