package com.llmetor.dataagent.dataagent.monitor;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 监控模块调度开关（M2/M5）：MetricAggregateJob（每 5 分钟指标聚合）与
 * ReportScheduleService（每日 09:00 日报）共用。本应用此前无定时任务，就近落在 monitor 包。
 */
@Configuration
@EnableScheduling
public class MonitorScheduleConfig {
}
