package com.hs.api.controller.payment;

import com.hs.api.advice.handler.GlobalException;
import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.model.PaymentProofUploadSession;
import com.hs.payment.model.constant.UploadSessionStatus;
import com.hs.payment.service.PaymentProofUploadSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentProofHandoffControllerTest {
    private static final String PATH = "/u/payment-proof/session";
    private static final String HEADER = "X-Proof-Upload-Token";
    private static final String TOKEN = "x".repeat(43);

    private final PaymentProofUploadSessionService uploads = mock(PaymentProofUploadSessionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PaymentProofHandoffController(uploads))
            .setControllerAdvice(new GlobalException()).build();

    @Test
    void returnsReadyAndAlreadyUploadedStates() throws Exception {
        var session = PaymentProofUploadSession.builder().status(UploadSessionStatus.CREATED).build();
        when(uploads.findValidSessionByRawToken(TOKEN)).thenReturn(session);

        mvc.perform(get(PATH).header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.state").value("READY"));

        session.setStatus(UploadSessionStatus.CONSUMED);
        mvc.perform(get(PATH).header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UPLOADED"));
    }

    @Test
    void requiresTokenHeader() throws Exception {
        mvc.perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(7018));
        verifyNoInteractions(uploads);
    }

    @Test
    void submitsMultipartAndReturnsJsonOnFailure() throws Exception {
        byte[] content = {1, 2, 3};
        var file = new MockMultipartFile("file", "proof.jpg", "image/jpeg", content);
        mvc.perform(multipart(PATH).file(file).header(HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UPLOADED"));
        verify(uploads).handleMobileUpload(TOKEN, content, "proof.jpg", "image/jpeg", 3L);

        when(uploads.handleMobileUpload(TOKEN, content, "proof.jpg", "image/jpeg", 3L))
                .thenThrow(new AppException(PaymentErrorCode.PROOF_SESSION_EXPIRED));
        mvc.perform(multipart(PATH).file(file).header(HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(7019));
    }
}
