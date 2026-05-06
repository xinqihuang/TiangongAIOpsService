package com.huawei.cloud.sre.monitor.repository;

import java.util.List;
import java.util.Optional;

/**
 * 动态基线存储接口（当前实现为内存，可替换为 JPA 实现）。
 */
public interface BaselineRepository {

    BaselineEntity save(BaselineEntity entity);

    Optional<BaselineEntity> findByServiceAndMetric(String service, String metric);

    List<BaselineEntity> findByService(String service);

    List<BaselineEntity> findAll();

    List<BaselineEntity> findUnstableBaselines(long minSamples);
}
