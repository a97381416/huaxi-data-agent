package com.llmetor.dataagent.dataagent.hitl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llmetor.dataagent.dataagent.action.ConfirmTokenService;
import com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import static com.llmetor.dataagent.dataagent.hitl.DecisionModels.*;
import com.llmetor.miniagentx.agent.ToolExecutionContext;
import com.llmetor.miniagentx.model.AgentStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一期 HITL 核心决策单与会话级授权服务：
 * <ul>
 *   <li>决策单持久化（agentx_decision），替代内存 ConfirmTokenService；</li>
 *   <li>安全防线：CAS 乐观锁、commandId 幂等、ownerUserId 归属校验、payloadHash 防篡改；</li>
 *   <li>会话级同类放行（agentx_decision_grant）：按 scopeKey 精确匹配，含 max_uses 配额、硬超时与一键撤销；</li>
 *   <li>生命周期管理：过期自动失效（定时+按需）、新消息 TakeOver 置为 SUPERSEDED。</li>
 * </ul>
 */
@Slf4j
@Service
public class DecisionService {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ConfirmTokenService confirmTokenService;
    private final HitlProperties properties;

    public DecisionService(DataSource dataSource,
                           ObjectMapper objectMapper,
                           ConfirmTokenService confirmTokenService,
                           HitlProperties properties) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.objectMapper = objectMapper;
        this.confirmTokenService = confirmTokenService;
        this.properties = properties;
        initSchema();
    }

    private void initSchema() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS agentx_decision (
                      decision_id        varchar(64)  NOT NULL,
                      conversation_id    varchar(100) NOT NULL,
                      session_id         varchar(100) NOT NULL,
                      tool_call_id       varchar(100) DEFAULT NULL,
                      agent_code         varchar(64)  DEFAULT NULL,
                      owner_user_id      bigint       NOT NULL,
                      type               varchar(32)  NOT NULL,
                      status             varchar(32)  NOT NULL,
                      revision           int          NOT NULL DEFAULT 1,
                      tool_name          varchar(100) NOT NULL,
                      scope_key          varchar(255) DEFAULT NULL,
                      action_summary     text         NOT NULL,
                      payload_json       text         NOT NULL,
                      payload_hash       varchar(128) NOT NULL,
                      options_json       text         DEFAULT NULL,
                      decision_option    varchar(64)  DEFAULT NULL,
                      answer_json        text         DEFAULT NULL,
                      user_words         text         DEFAULT NULL,
                      grant_id           varchar(64)  DEFAULT NULL,
                      answered_by        bigint       DEFAULT NULL,
                      answered_by_name   varchar(100) DEFAULT NULL,
                      client_ip          varchar(64)  DEFAULT NULL,
                      command_id         varchar(64)  DEFAULT NULL,
                      expires_at         timestamp    NOT NULL,
                      created_at         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      answered_at        timestamp    DEFAULT NULL,
                      consumed_at        timestamp    DEFAULT NULL,
                      PRIMARY KEY (decision_id)
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_decision_conv_status ON agentx_decision (conversation_id, status)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_decision_owner_status ON agentx_decision (owner_user_id, status, expires_at)");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_decision_cmd ON agentx_decision (decision_id, command_id) WHERE command_id IS NOT NULL");

            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS agentx_decision_grant (
                      grant_id           varchar(64)  NOT NULL,
                      user_id            bigint       NOT NULL,
                      conversation_id    varchar(100) NOT NULL,
                      source_decision_id varchar(64)  NOT NULL,
                      tool_name          varchar(100) NOT NULL,
                      scope_key          varchar(255) NOT NULL,
                      scope_desc         varchar(500) DEFAULT NULL,
                      status             varchar(20)  NOT NULL DEFAULT 'ACTIVE',
                      max_uses           int          NOT NULL DEFAULT 20,
                      used_count         int          NOT NULL DEFAULT 0,
                      expires_at         timestamp    NOT NULL,
                      created_at         timestamp    NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      revoked_at         timestamp    DEFAULT NULL,
                      PRIMARY KEY (grant_id)
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_grant_lookup ON agentx_decision_grant (user_id, conversation_id, scope_key, status)");
        } catch (Exception e) {
            log.warn("[hitl] 初始化 HITL 表结构失败（若已建表可忽略）: {}", e.getMessage());
        }
    }

    /** 是否启用 HITL（支持按工具粒度灰度） */
    public boolean isHitlEnabled(String toolName) {
        if (!properties.isEnabled()) {
            return false;
        }
        return properties.getDisabledTools() == null || !properties.getDisabledTools().contains(toolName);
    }

    /** 复用规范化 SHA-256 哈希算法 */
    public String payloadHash(Object payload) {
        return confirmTokenService.payloadHash(payload);
    }

    /**
     * 创建决策单；若允许会话级同类放行且命中有效授权，则自动扣减配额并落库 AUTO_APPROVED_BY_GRANT 记录。
     */
    public CreateResult createOrAutoApprove(CreateDecisionRequest req) {
        String rootConvId = resolveConversationId(req.conversationId());
        String toolCallId = StringUtils.hasText(req.toolCallId())
                ? req.toolCallId() : ToolExecutionContext.currentToolCallId();
        String agentCode = StringUtils.hasText(req.agentCode())
                ? req.agentCode() : ToolExecutionContext.currentAgentCode();
        String sessionId = StringUtils.hasText(req.sessionId()) ? req.sessionId() : rootConvId;

        int ttlMin = req.ttlMinutes() != null && req.ttlMinutes() > 0
                ? req.ttlMinutes() : properties.getDefaultTtlMinutes();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusMinutes(ttlMin);

        String payloadJson = toJson(req.rawPayload());
        String optionsJson = buildOptionsMetaJson(req);

        // 检查会话级同类放行
        DecisionGrantEntity matchedGrant = null;
        if (req.allowSessionGrant() && StringUtils.hasText(req.scopeKey())) {
            matchedGrant = tryConsumeGrant(req.ownerUserId(), rootConvId, req.scopeKey());
        }

        String decisionId = "dec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String status = matchedGrant != null ? STATUS_AUTO_APPROVED_BY_GRANT : STATUS_PENDING;
        String decisionOption = matchedGrant != null ? OPTION_ALLOW_SESSION : null;
        String grantId = matchedGrant != null ? matchedGrant.grantId() : null;
        LocalDateTime answeredAt = matchedGrant != null ? now : null;
        Long answeredBy = matchedGrant != null ? req.ownerUserId() : null;

        jdbc.update("""
                INSERT INTO agentx_decision (
                  decision_id, conversation_id, session_id, tool_call_id, agent_code, owner_user_id,
                  type, status, revision, tool_name, scope_key, action_summary, payload_json, payload_hash,
                  options_json, decision_option, grant_id, answered_by, expires_at, created_at, answered_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                decisionId, rootConvId, sessionId, toolCallId, agentCode, req.ownerUserId(),
                req.type(), status, req.toolName(), req.scopeKey(), req.actionSummary(),
                payloadJson, req.payloadHash(), optionsJson, decisionOption, grantId, answeredBy,
                Timestamp.valueOf(expiresAt), Timestamp.valueOf(now),
                answeredAt != null ? Timestamp.valueOf(answeredAt) : null);

        DecisionEntity entity = findById(decisionId);
        HitlCardPayload card = toCardPayload(entity, null);

        if (matchedGrant != null) {
            log.info("[hitl] 命中会话级同类放行: decisionId={}, grantId={}, tool={}, scopeKey={}, used={}/{}",
                    decisionId, matchedGrant.grantId(), req.toolName(), req.scopeKey(),
                    matchedGrant.usedCount(), matchedGrant.maxUses());
            return new CreateResult(entity, card, true, matchedGrant);
        }

        // 未命中放行：向主会话 SSE 流推送 HITL_REQUEST
        ToolExecutionContext.emit(AgentStreamEvent.hitlRequest(decisionId, req.type(), card));
        log.info("[hitl] 创建待决策单: decisionId={}, convId={}, userId={}, tool={}, allowSessionGrant={}",
                decisionId, rootConvId, req.ownerUserId(), req.toolName(), req.allowSessionGrant());
        return new CreateResult(entity, card, false, null);
    }

    /**
     * 人工签字审批（CAS 乐观锁 + 真人鉴权 + 幂等键 + 风险门控）。
     */
    public DecideResponse decide(String decisionId, long currentUserId, String currentUserName,
                                 String clientIp, DecideRequest req) {
        if (req == null || !StringUtils.hasText(req.option())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批选项不能为空");
        }
        String option = req.option().trim().toUpperCase();
        if (!OPTION_ALLOW_ONCE.equals(option) && !OPTION_ALLOW_SESSION.equals(option)
                && !OPTION_REJECTED.equals(option)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的审批选项: " + option);
        }

        DecisionEntity entity = findById(decisionId);
        if (entity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批单不存在: " + decisionId);
        }

        // 1. 归属权校验（严禁越权替他人审批）
        if (entity.ownerUserId() != currentUserId) {
            log.warn("[hitl] 越权审批拦截: decisionId={}, owner={}, actor={}",
                    decisionId, entity.ownerUserId(), currentUserId);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作他人的审批单");
        }

        // 2. commandId 幂等键检查（防双击并发重放）
        if (StringUtils.hasText(req.commandId()) && req.commandId().equals(entity.commandId())) {
            log.info("[hitl] 命中幂等重放: decisionId={}, commandId={}", decisionId, req.commandId());
            return buildDecideResponse(entity);
        }

        // 3. 过期校验
        LocalDateTime now = LocalDateTime.now();
        if (entity.expiresAt() != null && now.isAfter(entity.expiresAt())) {
            if (STATUS_PENDING.equals(entity.status())) {
                jdbc.update("UPDATE agentx_decision SET status = ?, revision = revision + 1 "
                                + "WHERE decision_id = ? AND status = ?",
                        STATUS_EXPIRED, decisionId, STATUS_PENDING);
            }
            throw new ResponseStatusException(HttpStatus.GONE, "EXPIRED: 该审批单已过期失效，请重新发起");
        }

        // 4. 状态校验
        if (!STATUS_PENDING.equals(entity.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该审批单当前状态不可操作（" + entity.status() + "），请刷新页面");
        }

        // 5. 乐观锁版本校验
        int expectedRev = req.revision() != null ? req.revision() : entity.revision();
        if (expectedRev != entity.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "审批单版本已变更，请刷新后重试");
        }

        // 6. L3 不可逆工具禁止本会话同类放行
        Map<String, Object> optsMeta = parseJsonMap(entity.optionsJson());
        boolean allowSessionGrant = Boolean.TRUE.equals(optsMeta.get("allowSessionGrant"));
        if (OPTION_ALLOW_SESSION.equals(option) && !allowSessionGrant) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该操作属于不可逆高危审批（L3），禁止本会话同类放行");
        }

        String targetStatus = OPTION_REJECTED.equals(option) ? STATUS_REJECTED : STATUS_APPROVED;
        String userWords = StringUtils.hasText(req.userWords()) ? req.userWords().trim() : null;
        String answerJsonStr = req.answerJson() != null ? toJson(req.answerJson()) : null;
        String cmdId = StringUtils.hasText(req.commandId()) ? req.commandId().trim() : null;

        // 7. 若选择本会话同类放行，先创建授权单
        String grantId = null;
        if (OPTION_ALLOW_SESSION.equals(option) && StringUtils.hasText(entity.scopeKey())) {
            grantId = "gr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String scopeDesc = stringVal(optsMeta.get("scopeDesc"));
            LocalDateTime grantExpires = now.plusHours(properties.getGrantTtlHours());
            jdbc.update("""
                    INSERT INTO agentx_decision_grant (
                      grant_id, user_id, conversation_id, source_decision_id, tool_name,
                      scope_key, scope_desc, status, max_uses, used_count, expires_at, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                    grantId, currentUserId, entity.conversationId(), decisionId, entity.toolName(),
                    entity.scopeKey(), scopeDesc, GRANT_ACTIVE, properties.getGrantMaxUses(),
                    Timestamp.valueOf(grantExpires), Timestamp.valueOf(now));
        }

        // 8. CAS 更新
        int updated = jdbc.update("""
                UPDATE agentx_decision
                SET status = ?, decision_option = ?, user_words = ?, answer_json = ?,
                    grant_id = ?, answered_by = ?, answered_by_name = ?, client_ip = ?,
                    command_id = ?, answered_at = ?, revision = revision + 1
                WHERE decision_id = ? AND status = ? AND revision = ?
                """,
                targetStatus, option, userWords, answerJsonStr,
                grantId, currentUserId, currentUserName, clientIp,
                cmdId, Timestamp.valueOf(now),
                decisionId, STATUS_PENDING, expectedRev);

        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "并发冲突：审批单已被其他操作处理");
        }

        DecisionEntity refreshed = findById(decisionId);
        log.info("[hitl] 用户审批完成: decisionId={}, option={}, status={}, actor={}({}), grantId={}",
                decisionId, option, targetStatus, currentUserName, currentUserId, grantId);
        return buildDecideResponse(refreshed);
    }

    /**
     * 校验并消费决策单（二轮工具执行前调用，保证一次性与防篡改）。
     */
    public ConsumeOutcome verifyAndConsume(String decisionId, long userId, String toolName, String payloadHash) {
        if (!StringUtils.hasText(decisionId)) {
            return new ConsumeOutcome(false, ConsumeStatus.NOT_FOUND, null, "缺少决策单 ID");
        }
        DecisionEntity entity = findById(decisionId.trim());
        if (entity == null) {
            return new ConsumeOutcome(false, ConsumeStatus.NOT_FOUND, null, "决策单不存在或已失效");
        }
        if (entity.ownerUserId() != userId) {
            return new ConsumeOutcome(false, ConsumeStatus.USER_MISMATCH, entity, "决策单归属用户不匹配");
        }
        if (!Objects.equals(entity.toolName(), toolName)) {
            return new ConsumeOutcome(false, ConsumeStatus.ACTION_MISMATCH, entity, "决策单动作类型不匹配");
        }
        if (STATUS_CONSUMED.equals(entity.status())) {
            return new ConsumeOutcome(false, ConsumeStatus.ALREADY_CONSUMED, entity, "该决策单已执行完成，不能重复执行");
        }
        if (STATUS_EXPIRED.equals(entity.status())
                || (entity.expiresAt() != null && LocalDateTime.now().isAfter(entity.expiresAt()))) {
            return new ConsumeOutcome(false, ConsumeStatus.EXPIRED, entity, "该决策单已过期失效");
        }
        if (!STATUS_APPROVED.equals(entity.status()) && !STATUS_AUTO_APPROVED_BY_GRANT.equals(entity.status())) {
            return new ConsumeOutcome(false, ConsumeStatus.NOT_APPROVED, entity,
                    "决策单当前状态为 " + entity.status() + "，尚未获得批准");
        }
        if (!Objects.equals(entity.payloadHash(), payloadHash)) {
            return new ConsumeOutcome(false, ConsumeStatus.PAYLOAD_MISMATCH, entity,
                    "业务数据或操作参数已发生变化（指纹不匹配），已拦截执行");
        }

        LocalDateTime now = LocalDateTime.now();
        int updated = jdbc.update("""
                UPDATE agentx_decision
                SET status = ?, consumed_at = ?, revision = revision + 1
                WHERE decision_id = ? AND status IN (?, ?) AND revision = ?
                """,
                STATUS_CONSUMED, Timestamp.valueOf(now),
                entity.decisionId(), STATUS_APPROVED, STATUS_AUTO_APPROVED_BY_GRANT, entity.revision());

        if (updated == 0) {
            return new ConsumeOutcome(false, ConsumeStatus.ALREADY_CONSUMED, entity, "并发冲突或已被消费");
        }

        DecisionEntity consumed = findById(entity.decisionId());
        HitlCardPayload card = toCardPayload(consumed, null);
        ToolExecutionContext.emit(AgentStreamEvent.hitlResolved(consumed.decisionId(), consumed.type(), card));
        log.info("[hitl] 决策单已核验消费: decisionId={}, tool={}, userId={}",
                consumed.decisionId(), toolName, userId);
        return new ConsumeOutcome(true, ConsumeStatus.OK, consumed, "OK");
    }

    /**
     * 用户发新消息 TakeOver：将该会话下尚未处理的 PENDING 决策单置为 SUPERSEDED。
     */
    public List<HitlCardPayload> markSuperseded(String conversationId, long userId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        List<DecisionEntity> pending = jdbc.query(
                "SELECT * FROM agentx_decision WHERE conversation_id = ? AND owner_user_id = ? AND status = ?",
                DECISION_ROW_MAPPER, conversationId, userId, STATUS_PENDING);
        if (pending.isEmpty()) {
            return List.of();
        }
        int rows = jdbc.update(
                "UPDATE agentx_decision SET status = ?, revision = revision + 1 "
                        + "WHERE conversation_id = ? AND owner_user_id = ? AND status = ?",
                STATUS_SUPERSEDED, conversationId, userId, STATUS_PENDING);
        log.info("[hitl] 新消息顶掉旧审批单: convId={}, userId={}, count={}", conversationId, userId, rows);
        return pending.stream().map(e -> {
            DecisionEntity updated = new DecisionEntity(
                    e.decisionId(), e.conversationId(), e.sessionId(), e.toolCallId(), e.agentCode(),
                    e.ownerUserId(), e.type(), STATUS_SUPERSEDED, e.revision() + 1, e.toolName(),
                    e.scopeKey(), e.actionSummary(), e.payloadJson(), e.payloadHash(), e.optionsJson(),
                    e.decisionOption(), e.answerJson(), e.userWords(), e.grantId(), e.answeredBy(),
                    e.answeredByName(), e.clientIp(), e.commandId(), e.expiresAt(), e.createdAt(),
                    e.answeredAt(), e.consumedAt());
            return toCardPayload(updated, null);
        }).toList();
    }

    /** 查询待办列表（支持按会话过滤或全局汇总，附带会话标题） */
    public List<HitlCardPayload> listPending(long userId, String conversationId) {
        // 先按需将已超时的 PENDING 置为 EXPIRED
        expireOverdueForUser(userId);
        String sql;
        Object[] args;
        if (StringUtils.hasText(conversationId)) {
            sql = """
                    SELECT d.*, (
                      SELECT c.question FROM agentx_conversation c
                      WHERE c.conversation_id = d.conversation_id
                      ORDER BY c.created_at ASC LIMIT 1
                    ) AS conv_title
                    FROM agentx_decision d
                    WHERE d.owner_user_id = ? AND d.conversation_id = ?
                      AND d.status = 'PENDING' AND d.expires_at > CURRENT_TIMESTAMP
                    ORDER BY d.created_at DESC
                    """;
            args = new Object[]{userId, conversationId.trim()};
        } else {
            sql = """
                    SELECT d.*, (
                      SELECT c.question FROM agentx_conversation c
                      WHERE c.conversation_id = d.conversation_id
                      ORDER BY c.created_at ASC LIMIT 1
                    ) AS conv_title
                    FROM agentx_decision d
                    WHERE d.owner_user_id = ?
                      AND d.status = 'PENDING' AND d.expires_at > CURRENT_TIMESTAMP
                    ORDER BY d.created_at DESC
                    """;
            args = new Object[]{userId};
        }
        return jdbc.query(sql, (rs, i) -> {
            DecisionEntity e = DECISION_ROW_MAPPER.mapRow(rs, i);
            String title = rs.getString("conv_title");
            if (title != null && title.length() > 30) {
                title = title.substring(0, 30) + "…";
            }
            return toCardPayload(e, title);
        }, args);
    }

    /** 查询指定会话的所有决策单（包含待决与历史留痕，用于刷新还原与审计回显） */
    public List<HitlCardPayload> listByConversation(long userId, String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return List.of();
        }
        expireOverdueForUser(userId);
        List<DecisionEntity> list = jdbc.query(
                "SELECT * FROM agentx_decision WHERE owner_user_id = ? AND conversation_id = ? ORDER BY created_at ASC",
                DECISION_ROW_MAPPER, userId, conversationId.trim());
        return list.stream().map(e -> toCardPayload(e, null)).toList();
    }

    /** 一键撤销会话级同类放行 */
    public boolean revokeGrant(String grantId, long userId) {
        if (!StringUtils.hasText(grantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "grantId 不能为空");
        }
        List<DecisionGrantEntity> grants = jdbc.query(
                "SELECT * FROM agentx_decision_grant WHERE grant_id = ?",
                GRANT_ROW_MAPPER, grantId.trim());
        if (grants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权记录不存在");
        }
        DecisionGrantEntity grant = grants.get(0);
        if (grant.userId() != userId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权撤销他人的授权");
        }
        int updated = jdbc.update(
                "UPDATE agentx_decision_grant SET status = ?, revoked_at = CURRENT_TIMESTAMP "
                        + "WHERE grant_id = ? AND status = ?",
                GRANT_REVOKED, grant.grantId(), GRANT_ACTIVE);
        log.info("[hitl] 撤销会话级授权: grantId={}, userId={}, updated={}", grantId, userId, updated);
        return updated > 0;
    }

    public DecisionEntity findById(String decisionId) {
        if (!StringUtils.hasText(decisionId)) {
            return null;
        }
        List<DecisionEntity> list = jdbc.query(
                "SELECT * FROM agentx_decision WHERE decision_id = ?",
                DECISION_ROW_MAPPER, decisionId.trim());
        return list.isEmpty() ? null : list.get(0);
    }

    public HitlCardPayload toCardPayload(DecisionEntity e, String convTitle) {
        if (e == null) {
            return null;
        }
        Map<String, Object> opts = parseJsonMap(e.optionsJson());
        Object rawPayload = parseJsonAny(e.payloadJson());
        List<KeyParamItem> keyParams = parseKeyParams(opts.get("keyParams"));
        String actionLabel = stringVal(opts.get("actionLabel"));
        String targetSummary = stringVal(opts.get("targetSummary"));
        String scopeDesc = stringVal(opts.get("scopeDesc"));
        boolean allowSessionGrant = Boolean.TRUE.equals(opts.get("allowSessionGrant"));

        long remainingSeconds = 0L;
        if (e.expiresAt() != null && STATUS_PENDING.equals(e.status())) {
            remainingSeconds = Math.max(0L, Duration.between(LocalDateTime.now(), e.expiresAt()).getSeconds());
        }

        return new HitlCardPayload(
                e.decisionId(),
                e.conversationId(),
                e.toolCallId(),
                e.agentCode(),
                e.type(),
                e.status(),
                e.revision(),
                e.toolName(),
                actionLabel,
                targetSummary,
                e.actionSummary(),
                keyParams,
                rawPayload,
                e.scopeKey(),
                scopeDesc,
                allowSessionGrant,
                e.decisionOption(),
                e.userWords(),
                e.grantId(),
                e.answeredByName(),
                formatTs(e.expiresAt()),
                formatTs(e.createdAt()),
                formatTs(e.answeredAt()),
                formatTs(e.consumedAt()),
                remainingSeconds,
                convTitle
        );
    }

    /** 定时扫描过期单据（每 5 分钟兜底清理） */
    @Scheduled(fixedRate = 300_000L)
    public void expireOverdueDecisions() {
        try {
            int count = jdbc.update(
                    "UPDATE agentx_decision SET status = ?, revision = revision + 1 "
                            + "WHERE status = ? AND expires_at <= CURRENT_TIMESTAMP",
                    STATUS_EXPIRED, STATUS_PENDING);
            if (count > 0) {
                log.info("[hitl] 定时过期清理完成: expiredCount={}", count);
            }
            jdbc.update(
                    "UPDATE agentx_decision_grant SET status = ? "
                            + "WHERE status = ? AND expires_at <= CURRENT_TIMESTAMP",
                    GRANT_EXPIRED, GRANT_ACTIVE);
        } catch (Exception e) {
            log.warn("[hitl] 定时过期清理异常: {}", e.getMessage());
        }
    }

    private void expireOverdueForUser(long userId) {
        jdbc.update(
                "UPDATE agentx_decision SET status = ?, revision = revision + 1 "
                        + "WHERE owner_user_id = ? AND status = ? AND expires_at <= CURRENT_TIMESTAMP",
                STATUS_EXPIRED, userId, STATUS_PENDING);
    }

    private DecisionGrantEntity tryConsumeGrant(long userId, String conversationId, String scopeKey) {
        List<DecisionGrantEntity> list = jdbc.query("""
                SELECT * FROM agentx_decision_grant
                WHERE user_id = ? AND conversation_id = ? AND scope_key = ?
                  AND status = 'ACTIVE' AND expires_at > CURRENT_TIMESTAMP AND used_count < max_uses
                ORDER BY created_at DESC LIMIT 1
                """, GRANT_ROW_MAPPER, userId, conversationId, scopeKey);
        if (list.isEmpty()) {
            return null;
        }
        DecisionGrantEntity grant = list.get(0);
        int updated = jdbc.update("""
                UPDATE agentx_decision_grant
                SET used_count = used_count + 1
                WHERE grant_id = ? AND status = 'ACTIVE' AND used_count < max_uses AND expires_at > CURRENT_TIMESTAMP
                """, grant.grantId());
        if (updated == 0) {
            return null;
        }
        return new DecisionGrantEntity(
                grant.grantId(), grant.userId(), grant.conversationId(), grant.sourceDecisionId(),
                grant.toolName(), grant.scopeKey(), grant.scopeDesc(), grant.status(),
                grant.maxUses(), grant.usedCount() + 1, grant.expiresAt(), grant.createdAt(), grant.revokedAt());
    }

    private DecideResponse buildDecideResponse(DecisionEntity entity) {
        String resumePrompt;
        if (STATUS_REJECTED.equals(entity.status())) {
            String reason = StringUtils.hasText(entity.userWords()) ? entity.userWords() : "未说明具体原因";
            resumePrompt = "用户已在审批卡片上【拒绝】执行决策单 " + entity.decisionId()
                    + "（" + entity.actionSummary() + "），拒绝理由：「" + reason
                    + "」。请不要调用写操作执行，并结合该拒绝理由向用户反馈调整建议或下一步方案。";
        } else {
            resumePrompt = "用户已在审批卡片上【批准】决策单 " + entity.decisionId()
                    + "（" + entity.actionSummary() + "）。请立即调用工具 `" + entity.toolName()
                    + "` 完成执行（confirmToken 请传入 \"" + entity.decisionId()
                    + "\"，并确保关联业务主键如 taskId/ledgerId 一致），然后向用户汇报执行结果。";
        }
        return new DecideResponse(
                entity.decisionId(),
                entity.status(),
                entity.revision(),
                "NEED_RESUME",
                entity.grantId(),
                entity.decisionOption(),
                entity.userWords(),
                entity.answeredByName(),
                formatTs(entity.answeredAt()),
                resumePrompt,
                toCardPayload(entity, null)
        );
    }

    private String resolveConversationId(String fallback) {
        String root = ToolExecutionContext.currentRootConversationId();
        return StringUtils.hasText(root) ? root : (StringUtils.hasText(fallback) ? fallback : "unknown");
    }

    private String buildOptionsMetaJson(CreateDecisionRequest req) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("actionLabel", req.actionLabel());
        meta.put("targetSummary", req.targetSummary());
        meta.put("keyParams", req.keyParams());
        meta.put("scopeDesc", req.scopeDesc());
        meta.put("allowSessionGrant", req.allowSessionGrant());
        return toJson(meta);
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Object parseJsonAny(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private List<KeyParamItem> parseKeyParams(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(item -> {
            if (item instanceof Map<?, ?> m) {
                return new KeyParamItem(stringVal(m.get("label")), stringVal(m.get("value")));
            }
            return null;
        }).filter(Objects::nonNull).toList();
    }

    private static String stringVal(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String formatTs(LocalDateTime dt) {
        return dt == null ? null : dt.format(TS_FMT);
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static final RowMapper<DecisionEntity> DECISION_ROW_MAPPER = new RowMapper<>() {
        @Override
        public DecisionEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            long ansBy = rs.getLong("answered_by");
            Long answeredBy = rs.wasNull() ? null : ansBy;
            return new DecisionEntity(
                    rs.getString("decision_id"),
                    rs.getString("conversation_id"),
                    rs.getString("session_id"),
                    rs.getString("tool_call_id"),
                    rs.getString("agent_code"),
                    rs.getLong("owner_user_id"),
                    rs.getString("type"),
                    rs.getString("status"),
                    rs.getInt("revision"),
                    rs.getString("tool_name"),
                    rs.getString("scope_key"),
                    rs.getString("action_summary"),
                    rs.getString("payload_json"),
                    rs.getString("payload_hash"),
                    rs.getString("options_json"),
                    rs.getString("decision_option"),
                    rs.getString("answer_json"),
                    rs.getString("user_words"),
                    rs.getString("grant_id"),
                    answeredBy,
                    rs.getString("answered_by_name"),
                    rs.getString("client_ip"),
                    rs.getString("command_id"),
                    toLocalDateTime(rs.getTimestamp("expires_at")),
                    toLocalDateTime(rs.getTimestamp("created_at")),
                    toLocalDateTime(rs.getTimestamp("answered_at")),
                    toLocalDateTime(rs.getTimestamp("consumed_at"))
            );
        }
    };

    private static final RowMapper<DecisionGrantEntity> GRANT_ROW_MAPPER = (rs, rowNum) -> new DecisionGrantEntity(
            rs.getString("grant_id"),
            rs.getLong("user_id"),
            rs.getString("conversation_id"),
            rs.getString("source_decision_id"),
            rs.getString("tool_name"),
            rs.getString("scope_key"),
            rs.getString("scope_desc"),
            rs.getString("status"),
            rs.getInt("max_uses"),
            rs.getInt("used_count"),
            toLocalDateTime(rs.getTimestamp("expires_at")),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("revoked_at"))
    );
}
