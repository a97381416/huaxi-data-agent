package com.llmetor.dataagent.dataagent.action;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 功能权限校验单测（M-E0）：H2（PostgreSQL 模式）真实跑权限四表 JOIN，
 * 覆盖：授予/未授予 / 多角色并集 / 停用角色剔除 / 停用菜单剔除 / type=1/2 菜单不计 /
 * 30s 缓存命中与过期 / 无权限话术。
 *
 * <p>数据骨架：
 * 用户 1001（申报人员，两角色 R1 启用 / R2 停用）；用户 1002（无任何角色）。
 * R1 授予：M1 按钮（doc:project:file:fill）/ M2 按钮（doc:warning-center:sign）/
 * M3 菜单（type=1，permission 不计）/ M4 停用按钮（不计）；R2 授予 M5 按钮（因角色停用不计）。
 */
class FunctionPermissionCheckerTest {

    private static final long USER_WITH_ROLES = 1001L;
    private static final long USER_NO_ROLES = 1002L;

    private JdbcTemplate jdbc;
    private FunctionPermissionChecker checker;

    @BeforeEach
    void setUp() {
        DataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:permcheck" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        seed(jdbc);
        checker = new FunctionPermissionChecker(ds, 30_000); // 生产默认 30s TTL
    }

    private void seed(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE system_menu (id bigint PRIMARY KEY, name varchar(50), "
                + "permission varchar(100), type smallint, sort int default 0, status smallint default 0, "
                + "deleted smallint default 0)");
        jdbc.execute("CREATE TABLE system_role (id bigint PRIMARY KEY, name varchar(50), "
                + "code varchar(100), status smallint default 0, deleted smallint default 0)");
        jdbc.execute("CREATE TABLE system_user_role (id bigint PRIMARY KEY, user_id bigint, role_id bigint, "
                + "deleted smallint default 0)");
        jdbc.execute("CREATE TABLE system_role_menu (id bigint PRIMARY KEY, role_id bigint, menu_id bigint, "
                + "deleted smallint default 0)");

        // 角色：R1 启用 / R2 停用
        jdbc.update("INSERT INTO system_role (id, name, code, status) VALUES (1, '申报人员', 'declarant', 0)");
        jdbc.update("INSERT INTO system_role (id, name, code, status) VALUES (2, '临时角色', 'tmp', 1)");

        // 用户-角色
        jdbc.update("INSERT INTO system_user_role (id, user_id, role_id) VALUES (1, 1001, 1)");
        jdbc.update("INSERT INTO system_user_role (id, user_id, role_id) VALUES (2, 1001, 2)");

        // 菜单：M1/M2 有效按钮；M3 目录型；M4 停用按钮；M5 只授给停用角色
        jdbc.update("INSERT INTO system_menu (id, name, permission, type, status) "
                + "VALUES (101, '资料填报', 'doc:project:file:fill', 3, 0)");
        jdbc.update("INSERT INTO system_menu (id, name, permission, type, status) "
                + "VALUES (102, '预警签收', 'doc:warning-center:sign', 3, 0)");
        jdbc.update("INSERT INTO system_menu (id, name, permission, type, status) "
                + "VALUES (103, '任务中心', 'task:center:view', 1, 0)");
        jdbc.update("INSERT INTO system_menu (id, name, permission, type, status) "
                + "VALUES (104, '停用按钮', 'doc:disabled:perm', 3, 1)");
        jdbc.update("INSERT INTO system_menu (id, name, permission, type, status) "
                + "VALUES (105, '删除项目', 'doc:project:delete', 3, 0)");

        // 角色-菜单
        jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id) VALUES (1, 1, 101)");
        jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id) VALUES (2, 1, 102)");
        jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id) VALUES (3, 1, 103)");
        jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id) VALUES (4, 1, 104)");
        jdbc.update("INSERT INTO system_role_menu (id, role_id, menu_id) VALUES (5, 2, 105)");
    }

    @Test
    void loadsOnlyEnabledButtonPermissions() {
        Set<String> perms = checker.loadPermissions(USER_WITH_ROLES);
        // M1/M2 命中；M3 目录不计、M4 停用不计、M5 停用角色的授予不计
        assertThat(perms).containsExactlyInAnyOrder("doc:project:file:fill", "doc:warning-center:sign");
    }

    @Test
    void hasPermissionGrantsAndDenies() {
        assertThat(checker.hasPermission(USER_WITH_ROLES, "doc:project:file:fill")).isTrue();
        assertThat(checker.hasPermission(USER_WITH_ROLES, "doc:project:delete")).isFalse();
        assertThat(checker.hasPermission(USER_NO_ROLES, "doc:project:file:fill")).isFalse();
        // 空权限点/空用户一律拒绝
        assertThat(checker.hasPermission(USER_WITH_ROLES, null)).isFalse();
        assertThat(checker.hasPermission(null, "doc:project:file:fill")).isFalse();
    }

    @Test
    void checkPermissionReturnsNullOnPassAndDenyMessageOnFail() {
        assertThat(checker.checkPermission(USER_WITH_ROLES, "doc:project:file:fill")).isNull();

        String deny = checker.checkPermission(USER_WITH_ROLES, "doc:project:delete");
        assertThat(deny).contains("没有执行该操作的权限");
        // 话术不泄露机制细节：不含表名/角色名/权限点字符串本身
        assertThat(deny).doesNotContain("system_", "role", "doc:project:delete");
    }

    @Test
    void cacheHitWithinTtlReflectsDbChangeOnlyAfterExpiry() {
        // 首查入缓存
        assertThat(checker.hasPermission(USER_WITH_ROLES, "doc:project:delete")).isFalse();

        // 主后端改权：DB 里直接授予 doc:project:delete（走 M5 按钮，先把角色 2 恢复启用）
        jdbc.update("UPDATE system_role SET status = 0 WHERE id = 2");

        // TTL 内仍是旧结果（缓存）
        assertThat(checker.hasPermission(USER_WITH_ROLES, "doc:project:delete"))
                .as("30s 缓存期内应命中旧缓存").isFalse();

        // 换短 TTL 实例验证过期后取新值
        FunctionPermissionChecker fresh = new FunctionPermissionChecker(
                jdbc.getDataSource(), 0); // TTL=0：每次必过期重查
        assertThat(fresh.hasPermission(USER_WITH_ROLES, "doc:project:delete"))
                .as("缓存过期后应看到新权限").isTrue();
    }

    @Test
    void evictForcesReload() {
        assertThat(checker.hasPermission(USER_WITH_ROLES, "doc:project:delete")).isFalse();
        jdbc.update("UPDATE system_role SET status = 0 WHERE id = 2");
        checker.evict(USER_WITH_ROLES); // 主动失效（预留的权限变更通知入口）
        assertThat(checker.hasPermission(USER_WITH_ROLES, "doc:project:delete")).isTrue();
    }
}
