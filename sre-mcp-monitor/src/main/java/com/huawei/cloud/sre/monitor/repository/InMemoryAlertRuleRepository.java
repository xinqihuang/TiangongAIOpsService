package com.huawei.cloud.sre.monitor.repository;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警规则内存存储实现（本地运行时使用，重启后数据丢失）。
 */
@Repository
public class InMemoryAlertRuleRepository implements AlertRuleRepository {

    private final ConcurrentHashMap<String, AlertRuleEntity> store = new ConcurrentHashMap<>();

    @Override
    public AlertRuleEntity save(AlertRuleEntity entity) {
        store.put(entity.getRuleId(), entity);
        return entity;
    }

    @Override
    public Optional<AlertRuleEntity> findById(String ruleId) {
        return Optional.ofNullable(store.get(ruleId));
    }

    @Override
    public boolean existsById(String ruleId) {
        return store.containsKey(ruleId);
    }

    @Override
    public void deleteById(String ruleId) {
        store.remove(ruleId);
    }

    @Override
    public List<AlertRuleEntity> findByEnabledTrue() {
        List<AlertRuleEntity> result = new ArrayList<>();
        for (AlertRuleEntity e : store.values()) {
            if (e.isEnabled()) result.add(e);
        }
        return result;
    }

    @Override
    public List<AlertRuleEntity> findByServiceAndEnabledTrue(String service) {
        List<AlertRuleEntity> result = new ArrayList<>();
        for (AlertRuleEntity e : store.values()) {
            if (e.isEnabled() && service.equals(e.getService())) result.add(e);
        }
        return result;
    }
}
