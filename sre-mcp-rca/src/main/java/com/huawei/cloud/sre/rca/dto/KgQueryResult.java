package com.huawei.cloud.sre.rca.dto;

import java.util.List;
import java.util.Map;

/**
 * 知识图谱 Cypher 查询结果。
 *
 * @param cypher          执行的 Cypher 语句
 * @param records         查询返回的记录列表；每条记录是一个 key→value 映射
 * @param totalCount      返回记录总条数
 * @param executionTimeMs 查询执行耗时（毫秒）
 */
public record KgQueryResult(
        String cypher,
        List<Map<String, Object>> records,
        int totalCount,
        long executionTimeMs
) {

    /** 查询是否返回了数据。 */
    public boolean hasResults() {
        return records != null && !records.isEmpty();
    }
}
