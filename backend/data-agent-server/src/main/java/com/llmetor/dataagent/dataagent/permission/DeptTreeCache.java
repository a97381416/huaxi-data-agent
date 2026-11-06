package com.llmetor.dataagent.dataagent.permission;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 部门树内存缓存（数据权限 DEPT_AND_SUB 的展开依赖）。
 *
 * <p>M-H2 华西版：数据源改为华西库 system_dept（yudao：status 0=启用 1=禁用；
 * 原自有 SysDept 的 'ACTIVE' 语义废弃）。启动加载一次；树未就绪时改写直接
 * 拒绝执行（fail-closed，宁可拒绝不可越权）。
 */
@Slf4j
@Getter
@Component
public class DeptTreeCache {

    private final JdbcTemplate jdbc;

    /** parentId → 直接子部门 ID 列表 */
    private Map<Long, List<Long>> children = Collections.emptyMap();

    /** 邻接表是否已加载成功 */
    private volatile boolean ready = false;

    public DeptTreeCache(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @PostConstruct
    public void load() {
        try {
            Map<Long, List<Long>> adjacency = new HashMap<>();
            // yudao system_dept：id/parent_id/status(0 启用)/deleted；parent_id=0 为根
            jdbc.query("SELECT id, parent_id FROM system_dept WHERE deleted = 0 AND status = 0",
                    rs -> {
                        long id = rs.getLong(1);
                        long parent = rs.getLong(2);
                        adjacency.computeIfAbsent(parent, k -> new java.util.ArrayList<>()).add(id);
                    });
            this.children = adjacency;
            this.ready = true;
            log.info("[dept-tree] system_dept 部门树加载完成：{} 个父节点", adjacency.size());
        } catch (Exception e) {
            // 启动阶段库未就绪（联调前）不阻断启动，改写时 fail-closed
            log.warn("[dept-tree] system_dept 加载失败（DEPT_AND_SUB 将拒绝执行）：{}", e.getMessage());
        }
    }

    /** 从一组部门向下展开子孙（含自身）。树未就绪抛出，由改写器拒绝执行。 */
    public Set<Long> expandWithDescendants(Set<Long> deptIds) {
        if (!ready) {
            throw new DeptTreeCacheNotReadyException("部门树缓存未就绪");
        }
        Set<Long> result = new HashSet<>(deptIds);
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(deptIds);
        while (!queue.isEmpty()) {
            Long cur = queue.poll();
            List<Long> subs = children.getOrDefault(cur, Collections.emptyList());
            for (Long sub : subs) {
                if (result.add(sub)) {
                    queue.add(sub);
                }
            }
        }
        return result;
    }
}
