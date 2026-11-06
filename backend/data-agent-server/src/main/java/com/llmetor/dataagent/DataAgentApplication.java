package com.llmetor.dataagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 数据分析智能体业务后端入口。
 */
@SpringBootApplication
@MapperScan({"com.llmetor.dataagent.sys.mapper", "com.llmetor.dataagent.dataagent.file",
        "com.llmetor.dataagent.dataagent.skill.mapper", "com.llmetor.dataagent.dataagent.mcp.mapper"})
public class DataAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataAgentApplication.class, args);
    }
}
