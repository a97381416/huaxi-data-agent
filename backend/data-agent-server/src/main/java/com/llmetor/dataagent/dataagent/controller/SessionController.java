package com.llmetor.dataagent.dataagent.controller;

import com.llmetor.dataagent.common.R;
import com.llmetor.dataagent.dataagent.session.ConversationDetailVO;
import com.llmetor.dataagent.dataagent.session.ConversationPage;
import com.llmetor.dataagent.dataagent.session.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话管理端点（M3）：历史列表、详情回放、删除。
 * 全部经 SessionService 做属主校验（只能看/删自己的会话）。
 */
@Slf4j
@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /**
     * 会话列表分页（侧栏展示，按最近活跃倒序；streaming 标记实时探测）。
     */
    @GetMapping("/list")
    public R<ConversationPage> list(
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "size", required = false, defaultValue = "10") int size) {
        return R.ok(sessionService.listConversations(page, size));
    }

    /**
     * 会话详情（所有问答轮次，含 answer 与 timeline 三类事件重构）。
     */
    @GetMapping("/{conversationId}")
    public R<ConversationDetailVO> detail(@PathVariable String conversationId) {
        ConversationDetailVO detail = sessionService.getConversation(conversationId);
        if (detail == null) {
            return R.notFound("会话不存在");
        }
        return R.ok(detail);
    }

    /**
     * 删除会话（该 conversationId 下所有轮次；级联消息行与中断快照）。
     */
    @DeleteMapping("/{conversationId}")
    public R<Void> delete(@PathVariable String conversationId) {
        boolean ok = sessionService.deleteConversation(conversationId);
        return ok ? R.ok() : R.notFound("会话不存在");
    }
}
