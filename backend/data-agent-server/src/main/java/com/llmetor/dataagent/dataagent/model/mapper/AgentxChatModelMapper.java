package com.llmetor.dataagent.dataagent.model.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmetor.dataagent.dataagent.model.AgentxChatModel;
import org.apache.ibatis.annotations.Mapper;

/**
 * agentx_chat_model 表 Mapper（MyBatis-Plus BaseMapper）。
 *
 * <p>对齐 mcp/mapper 包布局：@MapperScan 按包注册代理，
 * SPI/普通类与 Mapper 分包避免误注册（M5 踩坑固化）。
 */
@Mapper
public interface AgentxChatModelMapper extends BaseMapper<AgentxChatModel> {
}
