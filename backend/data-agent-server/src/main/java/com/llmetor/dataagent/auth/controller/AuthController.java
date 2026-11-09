package com.llmetor.dataagent.auth.controller;

import com.llmetor.dataagent.auth.HuaxiAuthContext;
import com.llmetor.dataagent.auth.dto.LoginUserVO;
import com.llmetor.dataagent.auth.service.HuaxiAuthException;
import com.llmetor.dataagent.auth.service.HuaxiAuthService;
import com.llmetor.dataagent.common.R;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;

/**
 * 华西登录接口（M-H2 / M-H4 前端对接）：agent 自有登录页调用；账号体系为华西
 * system_users（BCrypt 校验），token 按 yudao 协议签发进 system_oauth2_access_token——
 * 二期从华西前端跳转 SSO 时华西签发的 token 同表可直接通过 HuaxiAuthFilter 校验。
 *
 * <p>响应契约对齐上游 my-data-agent 与全站 R（code=200）：
 * POST /auth/login → R&lt;LoginUserVO&gt;、POST /auth/logout → R&lt;Void&gt;、GET /auth/me → R&lt;LoginUserVO&gt;。
 * 登录失败返回 R.paramError（400）——前端拦截器只对"会话过期"型 401 做跳转，
 * 登录页错误应原地展示文案而非触发未登录跳转。
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final HuaxiAuthService authService;
    private final JdbcTemplate jdbc;

    public AuthController(HuaxiAuthService authService, DataSource dataSource) {
        this.authService = authService;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public R<LoginUserVO> login(@RequestBody LoginRequest req) {
        try {
            return R.ok(authService.login(req.username(), req.password()));
        } catch (HuaxiAuthException e) {
            return R.paramError(e.getMessage());
        }
    }

    /**
     * 登出：删除 agent 自己签发的 token 行（client_id='huaxi-data-agent'）。
     * 华西主项目签发的 token（SSO 场景）不删——登出 agent 不应杀掉华西主会话。
     * 幂等：token 无效/缺失也返回成功。
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = extractToken(authorization);
        if (token != null) {
            try {
                jdbc.update("DELETE FROM system_oauth2_access_token "
                        + "WHERE access_token = ? AND client_id = 'huaxi-data-agent'", token);
            } catch (Exception ignored) {
                // 登出尽力而为：库异常不阻断前端清本地态
            }
        }
        return R.ok();
    }

    /** 当前登录用户完整信息（HuaxiAuthFilter 已校验 token 并注入上下文） */
    @GetMapping("/me")
    public R<LoginUserVO> me() {
        Long userId = HuaxiAuthContext.getCurrentUserIdOrNull();
        if (userId == null) {
            return R.unauthorized();
        }
        return R.ok(authService.buildLoginUser(userId, null));
    }

    /** Authorization: Bearer xxx（兼容裸 token 头）；无头返回 null */
    private static String extractToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String value = authorization.trim();
        return value.startsWith("Bearer ") ? value.substring(7).trim() : value;
    }
}
