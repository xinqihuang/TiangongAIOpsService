package com.huawei.cloud.sre.common.adapter;

import com.huawei.cloud.sre.common.exception.HuaweiCloudException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.scm.v3.ScmClient;
import com.huaweicloud.sdk.scm.v3.model.CertificateDetail;
import com.huaweicloud.sdk.scm.v3.model.ListCertificatesResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScmAdapterTest {

    @Mock
    private ScmClient client;

    private ScmAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ScmAdapter(client, new SimpleMeterRegistry());
    }

    @Test
    void listCertificates_returnsCertList() {
        var response = mock(ListCertificatesResponse.class);
        var cert = mock(CertificateDetail.class);

        when(client.listCertificates(any())).thenReturn(response);
        when(response.getCertificates()).thenReturn(List.of(cert));
        when(cert.getId()).thenReturn("cert-001");
        when(cert.getName()).thenReturn("*.example.com");
        when(cert.getDomain()).thenReturn("example.com");
        when(cert.getExpireTime()).thenReturn("2026-01-01T00:00:00Z");
        when(cert.getStatus()).thenReturn("ACTIVE");

        var result = adapter.listCertificates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("id")).isEqualTo("cert-001");
        assertThat(result.get(0).get("name")).isEqualTo("*.example.com");
        assertThat(result.get(0).get("domain")).isEqualTo("example.com");
        assertThat(result.get(0).get("expireTime")).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(result.get(0).get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void listCertificates_returnsEmpty_whenNull() {
        var response = mock(ListCertificatesResponse.class);
        when(client.listCertificates(any())).thenReturn(response);
        when(response.getCertificates()).thenReturn(null);

        var result = adapter.listCertificates();

        assertThat(result).isEmpty();
    }

    @Test
    void listCertificates_handlesNullFields() {
        var response = mock(ListCertificatesResponse.class);
        var cert = mock(CertificateDetail.class);

        when(client.listCertificates(any())).thenReturn(response);
        when(response.getCertificates()).thenReturn(List.of(cert));
        when(cert.getId()).thenReturn(null);
        when(cert.getName()).thenReturn(null);
        when(cert.getDomain()).thenReturn(null);
        when(cert.getExpireTime()).thenReturn(null);
        when(cert.getStatus()).thenReturn(null);

        var result = adapter.listCertificates();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("status")).isEqualTo("Unknown");
        assertThat(result.get(0).get("id")).isEmpty();
    }

    @Test
    void listCertificates_throwsHuaweiCloudException_onApiError() {
        when(client.listCertificates(any()))
                .thenThrow(new ServiceResponseException(500, "SCM Error", "SCM.0001", "req-013"));

        assertThatThrownBy(() -> adapter.listCertificates())
                .isInstanceOf(HuaweiCloudException.class)
                .hasMessageContaining("SCM");
    }
}
