package com.hs.api.controller.contract;
import com.hs.common.context.*;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.service.*;
import com.hs.contract.dto.billing.RentalWorkflowPayloads;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
class RentalWorkflowControllerTest {
    final PreTenancyResolutionService cancel=mock(PreTenancyResolutionService.class);
    final MutualTerminationService termination=mock(MutualTerminationService.class);
    final WorkflowDocumentService documents=mock(WorkflowDocumentService.class);
    final RentalWorkflowController controller=new RentalWorkflowController(cancel,termination,documents);
    @AfterEach void clear(){UserContextHolder.clear();}
    @Test void identityComesFromContextForEveryKind(){UserContextHolder.set(new UserContext("tenant",null));var action=new RentalWorkflowPayloads.Action(4L,"Đã kiểm tra nội dung cùng hiện trạng thực tế",true,false);controller.cancellation("r","ACCEPT",action);controller.termination("r","ACCEPT",action);verify(cancel).act("r","tenant","ACCEPT",action);verify(termination).act("r","tenant","ACCEPT",action);}
    @Test void anonymousNeverReachesAnyService(){assertThrows(AppException.class,()->controller.cancellation("r"));assertThrows(AppException.class,()->controller.termination("r"));verifyNoInteractions(cancel,termination);}
    @Test void missingRevisionAndInvalidBodyDoNotReachMoneyWorkflow() throws Exception {UserContextHolder.set(new UserContext("tenant",null));var mvc=MockMvcBuilders.standaloneSetup(controller).build();for(String kind:List.of("cancellation","early-exit")){mvc.perform(post("/rental-requests/r/workflow/"+kind+"/ACCEPT").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Đã đọc nội dung cần phản hồi\"}")).andExpect(status().isBadRequest());mvc.perform(post("/rental-requests/r/workflow/"+kind+"/ACCEPT").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":-1,\"reason\":\"ngắn\"}")).andExpect(status().isBadRequest());}verifyNoInteractions(cancel,termination);}
    @Test void removedMoveInEndpointsAreGoneAndUnknownRentPolicyFailsValidation() throws Exception {UserContextHolder.set(new UserContext("owner",null));var mvc=MockMvcBuilders.standaloneSetup(controller).build();for(String gone:List.of("move-in","move-in/CONFIRM"))mvc.perform(post("/rental-requests/r/workflow/"+gone).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());mvc.perform(post("/rental-requests/r/workflow/early-exit").contentType(MediaType.APPLICATION_JSON).content("{\"expectedRevision\":0,\"reason\":\"Hai bên thống nhất kết thúc\",\"handoverTerms\":\"Trả nhà đầy đủ tài sản\",\"endExclusive\":\"2026-12-20\",\"finalRentPolicy\":\"UNKNOWN\",\"rentRefunds\":[]}")).andExpect(status().isBadRequest());verifyNoInteractions(termination);}
    @Test void unspecifiedRentPolicyReachesServiceForNormalization() throws Exception {
        UserContextHolder.set(new UserContext("owner",null));
        var mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/rental-requests/r/workflow/early-exit").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":0,\"reason\":\"Hai bên thống nhất kết thúc\",\"endExclusive\":\"2026-12-20\",\"rentRefunds\":[]}"))
            .andExpect(status().isOk());
        verify(termination).propose(eq("r"),eq("owner"),argThat(p->p.finalRentPolicy()==null));
    }
}
