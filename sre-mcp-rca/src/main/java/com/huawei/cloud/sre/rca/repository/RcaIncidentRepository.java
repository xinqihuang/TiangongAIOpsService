package com.huawei.cloud.sre.rca.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * RCA 事故记录数据访问层。
 *
 * <p>提供按服务、严重等级、时间范围查询历史事故的接口，支持相似事故检索。
 */
@Repository
public interface RcaIncidentRepository extends JpaRepository<RcaIncidentEntity, UUID> {

    /**
     * 按服务名查询最近的事故记录，按创建时间倒序。
     *
     * @param service 服务名
     * @param limit   最大返回条数
     * @return 事故列表
     */
    @Query("SELECT r FROM RcaIncidentEntity r WHERE r.service = :service ORDER BY r.createdAt DESC LIMIT :limit")
    List<RcaIncidentEntity> findRecentByService(@Param("service") String service, @Param("limit") int limit);

    /**
     * 按严重等级和时间范围查询事故。
     *
     * @param severity  严重等级
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 事故列表
     */
    List<RcaIncidentEntity> findBySeverityAndCreatedAtBetween(
            String severity, Instant startTime, Instant endTime);

    /**
     * 按根因组件查询相似事故（用于辅助根因推断）。
     *
     * @param component 组件名
     * @return 事故列表
     */
    List<RcaIncidentEntity> findByRootCauseComponentOrderByCreatedAtDesc(String component);
}
