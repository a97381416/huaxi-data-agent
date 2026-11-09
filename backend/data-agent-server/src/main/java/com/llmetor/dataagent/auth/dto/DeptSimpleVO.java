package com.llmetor.dataagent.auth.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 部门简要信息（对齐上游契约；含完整部门路径，用于前端区分同名部门）。
 */
@Data
public class DeptSimpleVO {

    private Long id;

    private String name;

    /**
     * 完整部门路径（从顶级到当前部门，含自己）。
     */
    private List<String> path = new ArrayList<>();
}
