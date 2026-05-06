package com.huawei.cloud.sre.monitor.repository;

import java.util.List;
import java.util.Optional;

/**
 * 告警规则存储接口（当前实现为内存，可替换为 JPA 实现）。
 */
public interface AlertRuleRepository {

    AlertRuleEntity save(AlertRuleEntity entity);

    Optional<AlertRuleEntity> findById(String ruleId);

    boolean existsById(String ruleId);

    void deleteById(String ruleId);

    List<AlertRuleEntity> findByEnabledTrue();

    List<AlertRuleEntity> findByServiceAndEnabledTrue(String service);
}
