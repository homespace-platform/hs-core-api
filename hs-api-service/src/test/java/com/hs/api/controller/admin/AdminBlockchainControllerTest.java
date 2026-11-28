package com.hs.api.controller.admin;

import com.hs.api.advice.handler.GlobalException;
import com.hs.api.filter.UserContextFilter;
import com.hs.common.advice.entity.AppException;
import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.blockchain.api.*;
import com.hs.contract.blockchain.api.AdminBlockchainResponse.*;
import com.hs.payment.membership.AdminEthPaymentQueries;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class AdminBlockchainControllerTest {
    static final String BASE="/api/v1/admin/blockchain", SECRET="p6-test-secret";
    AnnotationConfigApplicationContext context;
    AdminBlockchainService service;
    MockMvc mvc;
    @Configuration @EnableMethodSecurity(proxyTargetClass=true) static class Config {
        @Bean AdminBlockchainService service() { return mock(AdminBlockchainService.class); }
        @Bean AdminBlockchainController controller(AdminBlockchainService s) { return new AdminBlockchainController(s); }
    }
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class);
        service=context.getBean(AdminBlockchainService.class);
        mvc=standaloneSetup(context.getBean(AdminBlockchainController.class)).setControllerAdvice(new GlobalException())
                .addFilters(new UserContextFilter(SECRET)).build();
    }
    @AfterEach void close() { context.close(); }
    static MockHttpServletRequestBuilder request(String route) {
        return route.endsWith("verify") || route.endsWith("integrity") ? post(BASE+route) : get(BASE+route);
    }
    static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r, String role, String authorities) {
        return r.header("X-Internal-Secret",SECRET).header("X-User-Id","admin-test")
                .header("X-User-Role",role).header("X-User-Authorities",authorities);
    }
    @Test void everyRouteRejectsAnonymousForgedUserAndNarrowAdmin() throws Exception {
        for (String route:List.of("/summary","/proofs","/proofs/pdf","/proofs/pdf/verify","/proofs/pdf/integrity","/membership-eth-payments")) {
            mvc.perform(request(route)).andExpect(status().isUnauthorized());
            mvc.perform(request(route).header("X-User-Id","forged").header("X-User-Role","ADMIN")
                    .header("X-User-Authorities","BLOCKCHAIN_VIEW,CONTRACT_VIEW")).andExpect(status().isUnauthorized());
            mvc.perform(auth(request(route),"USER","ADMIN,BLOCKCHAIN_VIEW,CONTRACT_VIEW")).andExpect(status().isForbidden());
            mvc.perform(auth(request(route),"ADMIN","SYSTEM_HEALTH_VIEW,CONTRACT_VIEW")).andExpect(status().isForbidden());
        }
        mvc.perform(auth(request("/proofs/pdf/integrity"),"ADMIN","BLOCKCHAIN_VIEW")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    @Test void listsUseBoundedProjectionPaginationAndRealPermission() throws Exception {
        var now=Instant.parse("2026-11-28T00:00:00Z");
        var row=new ProofRow("pdf","HD-1",2,"FAILED","0x"+"1".repeat(64),null,now,null,"OTHER");
        when(service.proofs("FAILED","HD-",now,now.plusSeconds(1),PageRequest.of(0,50)))
                .thenReturn(new PageImpl<>(List.of(row),PageRequest.of(0,50),1));
        var response=mvc.perform(auth(request("/proofs"),"ADMIN","BLOCKCHAIN_VIEW")
                .param("status","FAILED").param("contractNumber","HD-").param("from",now.toString())
                .param("to",now.plusSeconds(1).toString()).param("size","500"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50)).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(header().string("Cache-Control","private, no-store")).andReturn().getResponse().getContentAsString();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).get("result").get(0);
        var keys=new HashSet<String>(); json.fieldNames().forEachRemaining(keys::add);
        assertEquals(Set.of("documentId","contractNumber","documentKind","status","transactionHash","blockNumber","createdAt","confirmedAt","errorCode"),keys);
    }
    @Test void invalidFiltersAndClientHashesCannotReachServices() throws Exception {
        for (String[] param:new String[][]{{"page","0"},{"page","-1"},{"size","0"},{"status","OTHER"},{"from","yesterday"},
                {"contractNumber",""},{"ownerId","other"}})
            mvc.perform(auth(request("/proofs"),"ADMIN","BLOCKCHAIN_VIEW").param(param[0],param[1])).andExpect(status().isBadRequest());
        mvc.perform(auth(request("/proofs"),"ADMIN","BLOCKCHAIN_VIEW").param("page","1","2")).andExpect(status().isBadRequest());
        mvc.perform(auth(request("/proofs"),"ADMIN","BLOCKCHAIN_VIEW").param("from","2026-11-29T00:00:00Z").param("to","2026-11-28T00:00:00Z")).andExpect(status().isBadRequest());
        mvc.perform(auth(request("/proofs/pdf/verify"),"ADMIN","BLOCKCHAIN_VIEW").contentType("application/json").content("{\"documentHash\":\"forged\"}")).andExpect(status().isBadRequest());
        mvc.perform(auth(request("/membership-eth-payments"),"ADMIN","BLOCKCHAIN_VIEW").param("unsettled","yes")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void permittedActionsHaveEnvelopesAliasesAndNotFound() throws Exception {
        when(service.verify("pdf")).thenReturn(new Verification("pdf","UNAVAILABLE",null,null,null,null,null));
        when(service.integrity("pdf")).thenReturn(new DocumentIntegrityResponse("pdf","UNAVAILABLE",null,null,Instant.now(),"Unavailable"));
        when(service.proof("missing")).thenThrow(new AppException(ContractErrorCode.CONTRACT_DOCUMENT_NOT_FOUND));
        mvc.perform(auth(request("/proofs/pdf/verify"),"ADMIN","BLOCKCHAIN_VIEW")).andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("UNAVAILABLE")).andExpect(jsonPath("$.result.matches").value((Object)null));
        mvc.perform(auth(post("/admin/blockchain/proofs/pdf/integrity"),"ADMIN","BLOCKCHAIN_VIEW,CONTRACT_VIEW")).andExpect(status().isOk());
        mvc.perform(auth(request("/proofs/missing"),"ADMIN","BLOCKCHAIN_VIEW")).andExpect(status().isNotFound());
        mvc.perform(auth(get("/admin/blockchain/summary"),"ADMIN","BLOCKCHAIN_VIEW")).andExpect(status().isOk());
    }
    @Test void ethResponseContainsOnlyApprovedFields() throws Exception {
        var p=new AdminEthPaymentQueries.Payment("order","PRO","PLAN",new BigDecimal("123.00"),"123456789123456789",
                "0x"+"2".repeat(64),null,"PENDING",Instant.now(),10,11155111,"ASSUMED_SEPOLIA_NO_STORED_CHAIN_ID");
        when(service.payments(true,PageRequest.of(0,20))).thenReturn(new PageImpl<>(List.of(p)));
        String body=mvc.perform(auth(request("/membership-eth-payments"),"ADMIN","BLOCKCHAIN_VIEW").param("unsettled","true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result[0].amountWei").value("123456789123456789"))
                .andReturn().getResponse().getContentAsString();
        for(String forbidden:List.of("payerWallet","recipientWallet","signature","ownerId","privateKey")) assertFalse(body.contains(forbidden));
    }
    @Test void defaultEthViewAndSummaryIncludePaidTransactionsWithoutContractProofs() throws Exception {
        var now=Instant.parse("2026-11-28T00:00:00Z");
        var payment=new AdminEthPaymentQueries.Payment("paid-order","PRO","PLAN",new BigDecimal("80000"),"123456789123456789",
                "0x"+"a".repeat(64),now,"PAID",now,0,11155111,"ASSUMED_SEPOLIA_NO_STORED_CHAIN_ID");
        when(service.payments(false,PageRequest.of(0,20))).thenReturn(new PageImpl<>(List.of(payment)));
        when(service.summary()).thenReturn(new Summary(now,new Network(11155111,"Sepolia",null,false,"https://sepolia.etherscan.io"),
                Map.of("PENDING",0L,"SUBMITTED",0L,"CONFIRMED",0L,"FAILED",0L),0,null,0,Map.of(),0,null,
                new Integrity(Map.of(),"Latest explicit check only"),0,1,1,null,Map.of()));
        mvc.perform(auth(request("/membership-eth-payments"),"ADMIN","BLOCKCHAIN_VIEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.result[0].status").value("PAID"));
        mvc.perform(auth(request("/summary"),"ADMIN","BLOCKCHAIN_VIEW"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.ethTotal").value(1))
                .andExpect(jsonPath("$.result.ethPaid").value(1)).andExpect(jsonPath("$.result.ethUnsettled").value(0))
                .andExpect(jsonPath("$.result.counts.CONFIRMED").value(0));
    }
}
