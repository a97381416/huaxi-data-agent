package com.llmetor.dataagent.auth.controller;

import com.llmetor.dataagent.auth.service.HuaxiAuthService;
import com.llmetor.dataagent.auth.service.HuaxiAuthException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 华西登录接口（M-H2）：agent 自有登录页调用；账号体系为华西 system_users
 * （BCrypt 校验），token 按 yudao 协议签发进 system_oauth2_access_token——
 * 二期从华西前端跳转 SSO 时华西签发的 token 同表可直接通过 HuaxiAuthFilter 校验。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final HuaxiAuthService authService;

    public record LoginRequest(String username, String password) {
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest req) {
        try {
            String token = authService.login(req.username(), req.password());
            return Map.of("code", 0, "data", Map.of("accessToken", token), "msg", "");
        } catch (HuaxiAuthException e) {
            return Map.of("code", 401, "msg", e.getMessage());
        }
    }
}
