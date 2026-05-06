package com.huawei.cloud.sre.remediation.repository;

import com.huawei.cloud.sre.remediation.statemachine.RemediationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 修复工单 JPA 仓库。
 */
@Repository
public interface RemediationContextRepository extends JpaRepository<RemediationContext, String> {

    /**
     * 根据幂等键查找已有工单（幂等去重）。
     *
     * @param idempotencyKey 幂等键
     * @return 已有工单（若存在）
     */
    Optional<RemediationContext> findByIdempotencyKey(String idempotencyKey);

    /**
     * 查询指定状态的所有工单。
     *
     * @param state 目标状态
     * @return 工单列表
     */
    List<RemediationContext> findByState(RemediationState state);

    /**
     * 查询待审批工单列表（按创建时间升序）。
     *
     * @return 待审批工单
     */
    @Query("SELECT r FROM RemediationContext r WHERE r.state = 'PENDING_APPROVAL' ORDER BY r.createdAt ASC")
    List<RemediationContext> findPendingApprovals();

    /**
     * 查询指定服务的最近工单。
     *
     * @param targetService 目标服务名
     * @return 工单列表
     */
    List<RemediationContext> findByTargetServiceOrderByCreatedAtDesc(String targetService);

    /**
     * 查询活跃工单数（非终态）。
     *
     * @return 活跃工单数量
     */
    @Query("SELECT COUNT(r) FROM RemediationContext r WHERE r.state NOT IN " +
           "('COMPLETED', 'ROLLED_BACK', 'REJECTED', 'CANCELLED')")
    long countActiveContexts();
}
