package com.llmetor.dataagent.auth.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录用户完整信息（登录成功 + /auth/me 接口返回，对齐上游 my-data-agent 契约）。
 *
 * <ul>
 *   <li>roles：用户拥有的所有角色（多角色并集，含 code——前端 isAdmin 判定依赖）</li>
 *   <li>depts：主部门 + 兼职部门（system_user_concurrent_dept），含部门路径</li>
 *   <li>dataScope：多角色合并后的最大数据范围（ALL/DEPT_AND_SUB/DEPT/SELF/CUSTOM）</li>
 *   <li>profile：华西版无 user_profile 段（M-H2 去除），恒为 null，前端 v-if 兜底</li>
 *   <li>token：当前会话 token（仅登录响应携带；/auth/me 传 null）</li>
 * </ul>
 */
@Data
public class LoginUserVO {

    private Long id;

    private String username;

    private String nickname;

    /**
     * 状态：ACTIVE-正常、DISABLED-禁用
     */
    private String status;

    private List<RoleSimpleVO> roles = new ArrayList<>();

    private List<DeptSimpleVO> depts = new ArrayList<>();

    /**
     * 数据范围（多角色取最大值）
     */
    private String dataScope;

    /**
     * 用户档案（华西版无此段，恒为 null）
     */
    private Object profile;

    /**
     * 当前会话 token（yudao 协议 access token）
     */
    private String token;
}
