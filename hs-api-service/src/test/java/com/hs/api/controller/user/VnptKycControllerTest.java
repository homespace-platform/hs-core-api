package com.hs.api.controller.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import com.hs.user.config.vnpt.VnptKycProperties;
import com.hs.user.model.User;
import com.hs.user.service.KycService;
import com.hs.user.service.kyc.*;
import com.hs.user.utils.CurrentUserUtils;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VnptKycControllerTest {
    VnptKycStore store=mock(VnptKycStore.class);
    VnptKycProxy proxy=mock(VnptKycProxy.class);
    KycService kyc=mock(KycService.class);
    CurrentUserUtils identity=mock(CurrentUserUtils.class);
    ObjectMapper mapper=new ObjectMapper();
    VnptKycController controller=new VnptKycController(store,proxy,kyc,identity,mapper,new VnptKycProperties(true,"https://example.test","test","test","test",15,5,1));
    void actor(){User u=new User();u.setId("alice");when(identity.getCurrentUser()).thenReturn(u);}
    @Test void sessionAndStepsUseVerifiedActorNotPayloadIdentity() throws Exception {
        actor();controller.start();verify(store).start("alice");
        MockHttpServletRequest request=new MockHttpServletRequest();request.setContent("{\"userId\":\"bob\",\"img\":\"front\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        controller.step("attempt","card",request);verify(proxy).process(eq("alice"),eq("attempt"),eq("card"),any());
        controller.finish("attempt");verify(store).finalizeAttempt("alice","attempt");verify(kyc).getCurrentStatus("alice");
    }
    @Test void invalidAndOversizedJsonNeverReachProxy() {
        actor();MockHttpServletRequest invalid=new MockHttpServletRequest();invalid.setContent("not-json".getBytes());
        assertThrows(AppException.class,()->controller.step("attempt","face",invalid));
        MockHttpServletRequest oversized=new MockHttpServletRequest();oversized.setContent(new byte[4*1048576]);
        assertThrows(AppException.class,()->controller.step("attempt","face",oversized));verifyNoInteractions(proxy);
    }
    @Test void disabledActorCannotStartCaptureOrFinalize() {
        when(identity.getCurrentUser()).thenThrow(new AppException(com.hs.user.advice.entity.enums.UserErrorCode.USER_DISABLED));
        assertThrows(AppException.class,()->controller.start());assertThrows(AppException.class,()->controller.finish("attempt"));verifyNoInteractions(store,proxy,kyc);
    }
    @Test void uploadRejectsOversizedPartBeforeReadingItsBytes() throws Exception {
        var file=mock(org.springframework.web.multipart.MultipartFile.class);when(file.getSize()).thenReturn(6*1048576L);
        assertThrows(AppException.class,()->controller.upload("attempt",file));verify(file,never()).getBytes();verifyNoInteractions(proxy);
    }
    @Test void uploadPassesVendorScanContentTypeToProxy() throws Exception {
        actor();var file=new MockMultipartFile("file","blob","text","scan".getBytes());
        controller.upload("attempt",file);
        verify(proxy).upload("alice","attempt","scan".getBytes(),"text");
    }
    @Test void sdkHttpResponsesContainTheVendorObjectInsteadOfJsonNodeMetadata() throws Exception {
        actor();var result=mapper.readTree("{\"message\":\"IDG-00000000\",\"object\":{\"hash\":\"scan-hash\"}}");
        when(proxy.upload(eq("alice"),eq("attempt"),any(),eq("text"))).thenReturn(result);
        when(proxy.process(eq("alice"),eq("attempt"),eq("card"),any())).thenReturn(result);
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        var upload=mvc.perform(multipart("/users/me/kyc/vnpt/sessions/attempt/sdk/upload")
            .file(new MockMultipartFile("file","scan","text","scan".getBytes()))).andReturn().getResponse().getContentAsString();
        assertEquals("scan-hash",mapper.readTree(upload).path("object").path("hash").asText());
        var card=mvc.perform(post("/users/me/kyc/vnpt/sessions/attempt/sdk/card")
            .contentType("application/json").content("{\"img\":\"scan-hash\"}")).andReturn().getResponse().getContentAsString();
        assertEquals("scan-hash",mapper.readTree(card).path("object").path("hash").asText());
    }
}
