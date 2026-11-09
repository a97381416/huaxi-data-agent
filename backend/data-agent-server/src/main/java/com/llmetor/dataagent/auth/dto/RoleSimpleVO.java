package com.llmetor.dataagent.auth.dto;

import lombok.Data;

/**
 * 角色简要信息（对齐上游 my-data-agent 契约；不含 dataScope/sort/status 等内部字段）。
 */
@Data
public class RoleSimpleVO {

    private Long id;

    private String code;

    private String name;
}
