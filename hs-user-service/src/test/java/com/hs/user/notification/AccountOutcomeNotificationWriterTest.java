package com.hs.user.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.user.model.KycVerification;
import com.hs.user.model.constant.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountOutcomeNotificationWriterTest {
    final AccountNotificationOutboxRepository rows=mock(AccountNotificationOutboxRepository.class);
    final AccountNotificationWriter writer=new AccountNotificationWriter(rows,"test.events");
    final ObjectMapper json=new ObjectMapper();

    @Test void exportsMinimalKycAndMembershipEventsWithoutIdentityEvidence() throws Exception {
        var v=KycVerification.builder().id("973cb6de-a338-4700-845b-883db575faaa").userId("stored-user")
            .provider(KycProvider.VNPT).rejectionReason("PRIVATE_ID_OR_PROVIDER_REASON").build();
        v.setStatus(KycStatus.VERIFIED);writer.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_VERIFIED);
        v.setStatus(KycStatus.REJECTED);writer.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_REJECTED);
        v.setStatus(KycStatus.PENDING);writer.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_RETRY_REQUIRED);
        Instant expiry=Instant.parse("2026-12-01T00:00:00Z");
        for(var e:AccountNotificationWriter.MembershipExpiryEvent.values())writer.enqueueMembershipExpiry("stored-owner",expiry,e);
        var capture=ArgumentCaptor.forClass(AccountNotificationOutbox.class);verify(rows,times(5)).save(capture.capture());
        List<String> fixtures=new ArrayList<>();
        for(var row:capture.getAllValues()) {
            var event=json.readTree(row.getPayload());assertEquals(6,event.size());assertEquals(1,event.get("version").asInt());
            assertEquals(row.getUserId(),event.get("recipients").get(0).asText());assertEquals(1,event.get("recipients").size());
            assertEquals(row.getEventId(),event.get("eventId").asText());assertNotNull(UUID.fromString(row.getEventId()));
            boolean kyc=event.get("eventType").asText().startsWith("KYC_");assertEquals(1,event.get("data").size());
            if(kyc)assertEquals(v.getId(),event.get("data").get("attemptId").asText());else assertEquals("2026-12-01T00:00:00.000Z",event.get("data").get("expiresAt").asText());
            assertFalse(row.getPayload().contains("PRIVATE"));assertFalse(row.getPayload().contains("VNPT"));fixtures.add(row.getPayload());
        }
        Path fixture=Path.of("target","account-outcome-event-contracts.json");Files.createDirectories(fixture.getParent());
        Files.writeString(fixture,"["+String.join(",",fixtures)+"]");
    }
    @Test void sameOutcomeOrSameExpiryHasStableIdentityAndDoesNotRequeueButRenewalHasANewIdentity() {
        Set<String> sent=new HashSet<>();when(rows.existsByEventId(anyString())).thenAnswer(c->sent.contains(c.getArgument(0)));
        when(rows.save(any())).thenAnswer(c->{AccountNotificationOutbox row=c.getArgument(0);sent.add(row.getEventId());return row;});
        Instant expiry=Instant.parse("2026-12-01T00:00:00Z");
        writer.enqueueMembershipExpiry("owner",expiry,AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
        writer.enqueueMembershipExpiry("owner",expiry,AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
        writer.enqueueMembershipExpiry("owner",expiry.plusSeconds(30*86400L),AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRING_SOON);
        var v=KycVerification.builder().id("attempt").userId("user").provider(KycProvider.VNPT).status(KycStatus.VERIFIED).build();
        writer.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_VERIFIED);writer.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_VERIFIED);
        verify(rows,times(3)).save(any());assertEquals(3,sent.size());
        var saved=org.mockito.ArgumentCaptor.forClass(AccountNotificationOutbox.class);verify(rows,times(3)).save(saved.capture());
        assertTrue(saved.getAllValues().getFirst().getPayload().contains("\"data\":{\"expiresAt\":\"2026-12-01T00:00:00.000Z\"}"));
    }
    @Test void wrongKycOutcomeAndMissingRecipientAreRejected() {
        var v=KycVerification.builder().id("attempt").userId("user").provider(KycProvider.VNPT).status(KycStatus.PENDING).build();
        assertThrows(IllegalStateException.class,()->writer.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_VERIFIED));
        v.setStatus(KycStatus.VERIFIED);assertThrows(IllegalStateException.class,()->writer.enqueueKyc(v,AccountNotificationWriter.KycEvent.KYC_RETRY_REQUIRED));
        assertThrows(IllegalStateException.class,()->writer.enqueueMembershipExpiry(" ",Instant.now(),AccountNotificationWriter.MembershipExpiryEvent.MEMBERSHIP_EXPIRED));
        verifyNoInteractions(rows);
    }
}
