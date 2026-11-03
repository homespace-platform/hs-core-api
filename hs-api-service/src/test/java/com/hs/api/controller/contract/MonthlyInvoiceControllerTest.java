package com.hs.api.controller.contract;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.contract.dto.billing.*;
import com.hs.contract.service.MonthlyBillingService;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonthlyInvoiceControllerTest {
    final MonthlyBillingService billing=mock(MonthlyBillingService.class);
    final MonthlyInvoiceController controller=new MonthlyInvoiceController(billing);
    @AfterEach void clear(){UserContextHolder.clear();}
    @Test void authenticatedIdentityAndReviewedVersionAreForwardedForEveryNewAction(){
        UserContextHolder.set(new UserContext("person",null));
        var issue=new UnilateralTerminationPayloads.Issue("Thông báo quá hạn thanh toán tiền thuê");controller.unilateral("bill",issue);verify(billing).issueUnilateralTermination("bill","person",issue);
        var review=new UnilateralTerminationPayloads.Review(2,UnilateralTerminationNotice.Option.PAYMENT_EXTENSION,5,"Xin dời hạn để chờ lương về");controller.reviewUnilateral("bill","notice",review);verify(billing).requestTerminationReview("bill","notice","person",review);
        var decision=new UnilateralTerminationPayloads.Decision(3,false,"Không đồng ý cho dời hạn thêm");controller.decideUnilateral("bill","notice",decision);verify(billing).decideTerminationReview("bill","notice","person",decision);
        var version=new UnilateralTerminationPayloads.Version(4);controller.withdrawUnilateral("bill","notice",version);verify(billing).withdrawUnilateralTermination("bill","notice","person",version);
        var handover=new UnilateralTerminationPayloads.Handover(5,true,true);controller.handoverUnilateral("bill","notice",handover);verify(billing).completeUnilateralHandover("bill","notice","person",handover);
    }
    @Test void unauthenticatedRequestCannotReachBillingAndLegacyCreateCannotSilentlyBecomeUnilateral(){
        assertThrows(AppException.class,()->controller.unilateral("bill",new UnilateralTerminationPayloads.Issue("Không có tài khoản xác thực")));verifyNoInteractions(billing);
        UserContextHolder.set(new UserContext("owner",null));assertThrows(AppException.class,()->controller.proposeTermination("bill"));verifyNoInteractions(billing);
    }
    @Test void bindingRejectsMissingDecisionInvalidVersionUnknownOptionAndUnconfirmedHandover() throws Exception {
        UserContextHolder.set(new UserContext("owner",null));var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        String base="/api/v1/monthly-invoices/bill/unilateral-termination/notice/";
        for(String body:new String[]{"{\"expectedVersion\":1,\"reason\":\"Chủ nhà đã xem xét đầy đủ\"}","{\"expectedVersion\":0,\"approve\":false,\"reason\":\"Chủ nhà đã xem xét đầy đủ\"}","{\"expectedVersion\":1,\"approve\":false,\"reason\":\"ngắn\"}"})
            mvc.perform(post(base+"decision").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(base+"review").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"option\":\"CANCEL\",\"reason\":\"Lý do cần xem xét đầy đủ\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(base+"handover").contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1,\"vacantPossessionConfirmed\":true}")).andExpect(status().isBadRequest());
        verifyNoInteractions(billing);
    }
}
