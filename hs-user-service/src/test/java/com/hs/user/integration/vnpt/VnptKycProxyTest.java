package com.hs.user.integration.vnpt;

import com.fasterxml.jackson.databind.*;
import com.hs.common.advice.entity.AppException;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.hs.user.model.KycProviderStep;
import com.hs.user.service.kyc.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnptKycProxyTest {
    ObjectMapper mapper=new ObjectMapper();
    VnptKycClient client=mock(VnptKycClient.class);
    VnptKycStore store=mock(VnptKycStore.class);
    VnptKycProxy proxy=new VnptKycProxy(client,store,new VnptKycProperties(true,"https://example.test","test","test",15,5,1),mapper);
    @Test void browserCannotChooseUpstreamAuthCorrelationOrDocumentClass() throws Exception {
        when(store.prepare(eq("alice"),eq("attempt"),any(),eq(List.of("front","face")))).thenAnswer(call->{KycProviderStep s=call.getArgument(2);s.setState("RUNNING");s.setAttemptId("attempt");s.setClientSession("WEB-SDK_test_attempt_123");return s;});
        when(client.json(eq("/ai/v1/web/face/compare"),any())).thenReturn(mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"msg\":\"NOMATCH\"},\"private\":\"discard\"}"));
        var result=proxy.process("alice","attempt","compare",mapper.readTree("{\"img_front\":\"front\",\"img_face\":\"face\",\"client_session\":\"other-user\",\"Authorization\":\"foreign-secret\",\"url\":\"https://evil.test\"}"));
        var body=ArgumentCaptor.forClass(JsonNode.class);verify(client).json(eq("/ai/v1/web/face/compare"),body.capture());
        assertEquals("WEB-SDK_test_attempt_123",body.getValue().path("client_session").asText());assertEquals("e41-1b6d-45c9-9",body.getValue().path("token").asText());assertFalse(body.getValue().has("Authorization"));assertFalse(body.getValue().has("url"));
        assertEquals("NOMATCH",result.path("object").path("msg").asText());assertFalse(result.has("private"));
        assertThrows(AppException.class,()->proxy.process("alice","attempt","arbitrary",mapper.createObjectNode()));
        assertThrows(AppException.class,()->proxy.process("alice","attempt","ocr",mapper.readTree("{\"type\":5}")));
    }
    @Test void invalidOrOversizeImagesNeverCallProvider() {
        assertThrows(AppException.class,()->proxy.upload("alice","attempt",new byte[]{1,2,3},"image/jpeg"));
        assertThrows(AppException.class,()->proxy.upload("alice","attempt",new byte[1048577],"text"));verifyNoInteractions(client,store);
    }
    @Test void personalAndValidationFieldsReachTheStoreWithoutUnapprovedPayload() throws Exception {
        when(store.prepare(eq("alice"),eq("attempt"),any(),eq(List.of("front","back")))).thenAnswer(call -> {
            KycProviderStep step=call.getArgument(2);step.setAttemptId("attempt");step.setState("RUNNING");return step;
        });
        when(client.json(eq("/ai/v1/web/ocr/id"),any())).thenReturn(mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"id\":\"001234567890\",\"name\":\"NGUYEN VAN A\",\"birth_day\":\"01/01/2000\",\"id_probs\":\"[0.98]\",\"issue_date_prob\":0.99,\"private\":\"discard\"}}"));
        var response=proxy.process("alice","attempt","ocr",mapper.readTree("{\"img_front\":\"front\",\"img_back\":\"back\",\"type\":-1}"));
        assertEquals("NGUYEN VAN A",response.path("object").path("name").asText());
        assertEquals("01/01/2000",response.path("object").path("birth_day").asText());
        assertFalse(response.path("object").has("private"));
        verify(store).finish(eq("alice"),any(),contains("issue_date_prob"),isNull());
    }
    @Test void sdk3dScanBlobIsUploadedWithoutTreatingItAsAnImage() throws Exception {
        byte[] scan="scan-result".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(store.prepare(eq("alice"),eq("attempt"),any(),eq(List.of()))).thenAnswer(invocation->{
            KycProviderStep step=invocation.getArgument(2);assertEquals("scan3d",step.getKind());step.setAttemptId("attempt");step.setState("RUNNING");return step;
        });
        when(client.upload(scan,"text")).thenReturn(mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"hash\":\"scan-hash\"}}"));
        assertEquals("scan-hash",proxy.upload("alice","attempt",scan,"text").path("object").path("hash").asText());
        verify(store).finish(eq("alice"),any(),anyString(),eq("scan-hash"));
    }
    @Test void faceLivenessOnlyUsesAnUploadedScanReference() throws Exception {
        when(store.prepare(eq("alice"),eq("attempt"),any(),eq(List.of("face-hash","face-hash","scan-hash")))).thenAnswer(invocation->{
            KycProviderStep step=invocation.getArgument(2);step.setAttemptId("attempt");step.setState("RUNNING");return step;
        });
        when(client.json(eq("/ai/v1/web/face/liveness-3d"),any())).thenReturn(mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"liveness\":\"success\"}}"));
        String input="{\"near_img\":\"face-hash\",\"far_img\":\"face-hash\",\"scan3d\":\"scan-hash\"}";
        proxy.process("alice","attempt","face",mapper.readTree(input));
        var body=ArgumentCaptor.forClass(JsonNode.class);verify(client).json(eq("/ai/v1/web/face/liveness-3d"),body.capture());
        assertEquals("scan-hash",body.getValue().path("scan3d").asText());
        assertEquals("face-hash",body.getValue().path("near_img").asText());
        assertEquals("face-hash",body.getValue().path("far_img").asText());
        assertThrows(AppException.class,()->proxy.process("alice","attempt","face",mapper.readTree(input.replace("\"scan-hash\"","{}"))));
    }
    @Test void providerFailureMarksReservedCallAndCannotBeConfusedWithSuccess() throws Exception {
        when(store.prepare(anyString(),anyString(),any(),anyList())).thenAnswer(call->{KycProviderStep s=call.getArgument(2);s.setState("RUNNING");s.setAttemptId("attempt");return s;});
        when(client.json(anyString(),any())).thenThrow(new AppException(com.hs.user.advice.entity.enums.UserErrorCode.KYC_PROVIDER_ERROR));
        assertThrows(AppException.class,()->proxy.process("alice","attempt","card",mapper.readTree("{\"img\":\"front\"}")));
        verify(store).fail(eq("alice"),any());verify(store,never()).finish(any(),any(),any(),any());
    }
}
