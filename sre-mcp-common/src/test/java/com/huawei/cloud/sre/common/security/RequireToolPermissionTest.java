package com.huawei.cloud.sre.common.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class RequireToolPermissionTest {

    @RequireToolPermission(scope = "rca:read")
    public void methodWithScope() {}

    @RequireToolPermission(scope = "remediation:write", message = "自定义消息")
    public void methodWithScopeAndMessage() {}

    @Test
    void annotation_defaultMessage_isEmpty() throws NoSuchMethodException {
        Method m = RequireToolPermissionTest.class.getMethod("methodWithScope");
        RequireToolPermission ann = m.getAnnotation(RequireToolPermission.class);
        assertThat(ann.scope()).isEqualTo("rca:read");
        assertThat(ann.message()).isEmpty();
    }

    @Test
    void annotation_customMessage_isSet() throws NoSuchMethodException {
        Method m = RequireToolPermissionTest.class.getMethod("methodWithScopeAndMessage");
        RequireToolPermission ann = m.getAnnotation(RequireToolPermission.class);
        assertThat(ann.scope()).isEqualTo("remediation:write");
        assertThat(ann.message()).isEqualTo("自定义消息");
    }
}
