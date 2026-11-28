package com.hs.contract.blockchain.api;

import com.hs.common.advice.entity.AppException;
import com.hs.common.context.*;
import com.hs.contract.blockchain.client.RegistryClient;
import com.hs.contract.blockchain.config.BlockchainProperties;
import com.hs.payment.membership.AdminEthPaymentQueries;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminBlockchainServiceTest {
    final AdminBlockchainQueries reads=mock();
    final AdminEthPaymentQueries payments=mock();
    final RegistryClient registry=mock();
    final ContractDocumentIntegrityService integrity=mock();
    final BlockchainProperties config=new BlockchainProperties();
    final AdminBlockchainService service=new AdminBlockchainService(reads,payments,config,registry,integrity);
    static final String HASH="0x"+"a".repeat(64), ID="0x"+"b".repeat(64), ADDRESS="0x"+"1".repeat(40);
    static final Instant NOW=Instant.parse("2026-11-28T00:00:00Z");
    @BeforeEach void setup() {
        UserContextHolder.set(new UserContext("admin",null));
        login("ADMIN","BLOCKCHAIN_VIEW");
        when(reads.proof("pdf")).thenReturn(Optional.of(new AdminBlockchainResponse.ProofDetail("pdf","HD",2,"CONFIRMED",HASH,ID,
                11155111,ADDRESS,null,null,null,null,NOW,null,null,null,1,null)));
    }
    static void login(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("admin",null,
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }
    @AfterEach void clear() { UserContextHolder.clear(); SecurityContextHolder.clearContext(); }
    @Test void verificationComparesOnlyStoredHashAndReportsConflicts() {
        when(registry.readDocument(11155111,ADDRESS,ID)).thenReturn(new RegistryClient.ChainProof(HASH,NOW));
        assertEquals(Boolean.TRUE,service.verify("pdf").matches());
        when(registry.readDocument(11155111,ADDRESS,ID)).thenReturn(new RegistryClient.ChainProof(ID,NOW));
        var conflict=service.verify("pdf"); assertFalse(conflict.matches()); assertTrue(conflict.conflict());
        when(registry.readDocument(11155111,ADDRESS,ID)).thenReturn(new RegistryClient.ChainProof("0x"+"0".repeat(64),null));
        var absent=service.verify("pdf"); assertFalse(absent.registered()); assertFalse(absent.conflict());
    }
    @Test void providerFailuresAndMalformedResultsAreUnavailableNotMismatch() {
        when(registry.readDocument(anyLong(),anyString(),anyString())).thenThrow(new RuntimeException("https://secret.example/key"));
        var result=service.verify("pdf"); assertEquals("UNAVAILABLE",result.status()); assertNull(result.matches());
        assertFalse(result.toString().contains("secret"));
        doReturn(new RegistryClient.ChainProof("invalid",null)).when(registry).readDocument(anyLong(),anyString(),anyString());
        assertEquals("UNAVAILABLE",service.verify("pdf").status());
    }
    @Test void missingProofNeverEnqueuesOrCallsProvider() {
        assertEquals(404,assertThrows(AppException.class,()->service.verify("missing")).getStatusCode().value());
        verifyNoInteractions(registry,integrity);
    }
    @Test void summaryUsesRealOpsClockNoProvidersAndWhitelistedExplorer() {
        when(reads.queue(NOW)).thenReturn(new AdminBlockchainQueries.Queue(Map.of("PENDING",2L),1,NOW.minusSeconds(900),1,Map.of()));
        when(reads.integrityCounts()).thenReturn(Map.of("MISMATCH",1L));
        when(payments.counts()).thenReturn(new AdminEthPaymentQueries.Counts(1,1,0));
        config.setRegistryAddress(ADDRESS); config.setRpcUrl("https://secret.example/key"); config.setPrivateKey("secret");
        var summary=service.summary(NOW);
        assertEquals(900,summary.oldestOpenAgeSeconds()); assertNull(summary.workerLastRunAgeSeconds());
        assertEquals(1,summary.ethTotal()); assertEquals(1,summary.ethPaid()); assertEquals(0,summary.ethUnsettled());
        assertEquals("https://sepolia.etherscan.io",summary.network().explorerBaseUrl()); assertFalse(summary.toString().contains("secret"));
        config.setChainId(999); config.setRegistryAddress("https://evil.example");
        assertNull(service.summary(NOW).network().explorerBaseUrl()); assertNull(service.summary(NOW).network().registryAddress());
        verifyNoInteractions(registry,integrity);
    }
    @Test void sharedQuotaIsTenPerRollingMinuteAndExpiredSlotsAreReusable() {
        for(int i=0;i<10;i++) service.limit("a",NOW);
        assertEquals(429,assertThrows(AppException.class,()->service.limit("a",NOW.plusSeconds(59))).getStatusCode().value());
        service.limit("b",NOW); service.limit("a",NOW.plusSeconds(60));
    }
    @Test void serviceBoundaryRequiresVerifiedAdminAndExtraIntegrityPermission() {
        login("USER","BLOCKCHAIN_VIEW"); assertThrows(AppException.class,()->service.proof("pdf"));
        login("ADMIN","SYSTEM_HEALTH_VIEW"); assertThrows(AppException.class,()->service.summary());
        login("ADMIN","BLOCKCHAIN_VIEW"); assertThrows(AppException.class,()->service.integrity("pdf"));
        UserContextHolder.clear(); assertEquals(401,assertThrows(AppException.class,()->service.proof("pdf")).getStatusCode().value());
        verifyNoInteractions(registry,integrity);
    }
    @Test void projectionSanitizesUnknownErrorAndUntrustedLinks() {
        assertEquals("OTHER",AdminBlockchainQueries.safeError("https://secret.example"));
        assertEquals("RPC_UNAVAILABLE",AdminBlockchainQueries.safeError("RPC_UNAVAILABLE"));
        assertNull(AdminBlockchainQueries.hash(HASH+"?secret")); assertNull(AdminBlockchainQueries.address(ADDRESS+"/"));
    }
}
