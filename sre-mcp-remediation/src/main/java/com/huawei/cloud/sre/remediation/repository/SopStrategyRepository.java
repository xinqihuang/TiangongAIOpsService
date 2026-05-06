package com.huawei.cloud.sre.remediation.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * SOP 策略库 JPA 仓库。
 */
@Repository
public interface SopStrategyRepository extends JpaRepository<SopStrategy, String> {

    /**
     * 根据名称查找策略。
     *
     * @param name 策略名称
     * @return 策略（若存在）
     */
    Optional<SopStrategy> findByName(String name);

    /**
     * 查询指定风险级别的所有策略（按优先级升序）。
     *
     * @param riskLevel 风险级别
     * @return 策略列表
     */
    List<SopStrategy> findByRiskLevelOrderByPriorityAsc(String riskLevel);

    /**
     * 查询适用于指定 Tool 的策略。
     *
     * @param toolName Tool 名称
     * @return 策略列表
     */
    List<SopStrategy> findByToolName(String toolName);

    /**
     * 全库搜索症状关键词（模糊匹配）。
     *
     * @param keyword 关键词
     * @return 匹配的策略列表（按优先级升序）
     */
    @Query("SELECT s FROM SopStrategy s WHERE LOWER(s.symptomKeywords) LIKE LOWER(CONCAT('%', :keyword, '%')) ORDER BY s.priority ASC")
    List<SopStrategy> searchByKeyword(@Param("keyword") String keyword);
}
