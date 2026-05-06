package com.huawei.cloud.sre.monitor.repository;

import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态基线内存存储实现（本地运行时使用，重启后数据丢失）。
 */
@Repository
public class InMemoryBaselineRepository implements BaselineRepository {

    private final ConcurrentHashMap<String, BaselineEntity> store = new ConcurrentHashMap<>();

    @Override
    public BaselineEntity save(BaselineEntity entity) {
        store.put(entity.getBaselineKey(), entity);
        return entity;
    }

    @Override
    public Optional<BaselineEntity> findByServiceAndMetric(String service, String metric) {
        return Optional.ofNullable(store.get(service + ":" + metric));
    }

    @Override
    public List<BaselineEntity> findByService(String service) {
        List<BaselineEntity> result = new ArrayList<>();
        for (BaselineEntity e : store.values()) {
            if (service.equals(e.getService())) result.add(e);
        }
        return result;
    }

    @Override
    public List<BaselineEntity> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<BaselineEntity> findUnstableBaselines(long minSamples) {
        List<BaselineEntity> result = new ArrayList<>();
        for (BaselineEntity e : store.values()) {
            if (e.getSampleCount() < minSamples) result.add(e);
        }
        return result;
    }
}
