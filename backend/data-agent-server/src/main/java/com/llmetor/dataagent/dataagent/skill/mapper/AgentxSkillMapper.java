package com.llmetor.dataagent.dataagent.skill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llmetor.dataagent.dataagent.skill.AgentxSkill;
import org.apache.ibatis.annotations.Mapper;

/**
 * agentx_skill 表 Mapper（MyBatis-Plus BaseMapper）。
 *
 * <p>刻意放独立 mapper 子包：@MapperScan 按包注册所有接口为 Mapper 代理，
 * 与 {@link com.llmetor.dataagent.dataagent.skill.SkillContentStore} SPI 同包会把它误注册
 * （启动报"found 2 beans"），故对齐 sys/mapper 包布局。
 */
@Mapper
public interface AgentxSkillMapper extends BaseMapper<AgentxSkill> {
}
