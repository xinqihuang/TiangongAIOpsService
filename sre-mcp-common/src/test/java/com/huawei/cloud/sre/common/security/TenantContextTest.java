package com.huawei.cloud.sre.common.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void set_and_get_returnSetValues() {
        TenantContext.set("tenant-abc", "user-xyz", "rca:read remediation:write");
        assertThat(TenantContext.getTenantId()).isEqualTo("tenant-abc");
        assertThat(TenantContext.getUserId()).isEqualTo("user-xyz");
        assertThat(TenantContext.getScope()).isEqualTo("rca:read remediation:write");
    }

    @Test
    void defaults_whenNotSet() {
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
        assertThat(TenantContext.getUserId()).isEqualTo("anonymous");
        assertThat(TenantContext.getScope()).isEmpty();
    }

    @Test
    void hasScope_withMatchingScope_returnsTrue() {
        TenantContext.set("t1", "u1", "rca:read remediation:write");
        assertThat(TenantContext.hasScope("rca:read")).isTrue();
        assertThat(TenantContext.hasScope("remediation:write")).isTrue();
    }

    @Test
    void hasScope_withMissingScope_returnsFalse() {
        TenantContext.set("t1", "u1", "rca:read");
        assertThat(TenantContext.hasScope("remediation:write")).isFalse();
    }

    @Test
    void hasScope_withBlankScope_returnsFalse() {
        TenantContext.set("t1", "u1", "");
        assertThat(TenantContext.hasScope("rca:read")).isFalse();
    }

    @Test
    void clear_removesAllValues() {
        TenantContext.set("t1", "u1", "scope1");
        TenantContext.clear();
        assertThat(TenantContext.getTenantId()).isEqualTo("default");
        assertThat(TenantContext.getUserId()).isEqualTo("anonymous");
    }
}
