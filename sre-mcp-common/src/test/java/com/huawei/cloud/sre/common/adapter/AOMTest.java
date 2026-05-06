package com.huawei.cloud.sre.common.adapter;

import com.huaweicloud.sdk.core.auth.ICredential;
import com.huaweicloud.sdk.core.auth.BasicCredentials;
import com.huaweicloud.sdk.core.exception.ConnectionException;
import com.huaweicloud.sdk.core.exception.RequestTimeoutException;
import com.huaweicloud.sdk.core.exception.ServiceResponseException;
import com.huaweicloud.sdk.aom.v2.region.AomRegion;
import com.huaweicloud.sdk.aom.v2.*;
import com.huaweicloud.sdk.aom.v2.model.*;


public class AOMTest {

    public static void main(String[] args) {
        // The AK and SK used for authentication are hard-coded or stored in plaintext, which has great security risks. It is recommended that the AK and SK be stored in ciphertext in configuration files or environment variables and decrypted during use to ensure security.
        // In this example, AK and SK are stored in environment variables for authentication. Before running this example, set environment variables CLOUD_SDK_AK and CLOUD_SDK_SK in the local environment
        String ak = "HPUAY21EIYEM8I309V1S";
        String sk = "9xK4vamP47HrlhV3SiK1Y29hbbfCXs3jlfhtlR9C";

        ICredential auth = new BasicCredentials()
                .withAk(ak)
                .withSk(sk)
                .withProjectId("936beb09bfff04ea984855281a7b162f9");

        AomClient client = AomClient.newBuilder()
                .withCredential(auth)
                .withRegion(AomRegion.valueOf("cn-north-5"))
                .build();
        ListEventsRequest request = new ListEventsRequest();
        EventQueryParam2 body = new EventQueryParam2();
        request.withBody(body);
        try {
            ListEventsResponse response = client.listEvents(request);
            System.out.println(response.toString());
        } catch (ConnectionException e) {
            e.printStackTrace();
        } catch (RequestTimeoutException e) {
            e.printStackTrace();
        } catch (ServiceResponseException e) {
            e.printStackTrace();
            System.out.println(e.getHttpStatusCode());
            System.out.println(e.getRequestId());
            System.out.println(e.getErrorCode());
            System.out.println(e.getErrorMsg());
        }
    }
}
