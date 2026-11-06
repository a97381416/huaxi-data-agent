package com.llmetor.dataagent.dataagent.mcp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmetor.dataagent.dataagent.mcp.AgentxMcpServer;
import org.apache.ibatis.annotations.Mapper;

/**
 * agentx_mcp_server 表 Mapper（MyBatis-Plus BaseMapper）。
 *
 * <p>对齐 skill/mapper 包布局：@MapperScan 按包注册代理，
 * SPI/普通类与 Mapper 分包避免误注册（M5 踩坑固化）。
 */
@Mapper
public interface AgentxMcpServerMapper extends BaseMapper<AgentxMcpServer> {
}
