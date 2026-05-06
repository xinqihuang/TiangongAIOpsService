package com.huawei.cloud.sre.monitor.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 定时任务调度（{@link EnableScheduling}）。
 *
 * <p>与 Virtual Threads 协同工作：Spring Boot 3.2+ 在启用虚拟线程时会自动将
 * {@code @Scheduled} 任务提交到虚拟线程执行器，无需额外配置。
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
