package com.hs.user.notification;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.user.model.User;

/**
 * Enqueues v1 account notifications for the persisted user whose role or status an admin changed. Callers decide
 * explicitly which committed transition produces an event; this class never infers events from entity state.
 * The envelope carries empty data: the recipient id is the only identity, so no role, email or admin id leaks.
 */
@Service
public class AccountNotificationWriter {
    public enum Event { ACCOUNT_ROLE_CHANGED, ACCOUNT_DISABLED, ACCOUNT_ENABLED }
    public enum KycEvent { KYC_VERIFIED, KYC_REJECTED, KYC_RETRY_REQUIRED }
    public enum MembershipExpiryEvent { MEMBERSHIP_EXPIRING_SOON, MEMBERSHIP_EXPIRED }

    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private final ObjectMapper json = new ObjectMapper();
    private final AccountNotificationOutboxRepository outbox;
    private final String topic;

    public AccountNotificationWriter(AccountNotificationOutboxRepository outbox,
            @Value("${notification.events-topic:homespace.notification.events.v1}") String topic) {
        this.outbox = outbox;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(User recipient, String actorId, Event type) {
        String recipientId = recipient.getId();
        if (recipientId == null || recipientId.isBlank()) throw new IllegalStateException("Account recipient missing");
        if (recipientId.equals(actorId)) return; // the admin already knows about their own action
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("eventType", type.name());
        envelope.put("version", 1);
        envelope.put("occurredAt", TIMESTAMP.format(now));
        envelope.put("recipients", List.of(recipientId));
        envelope.put("data", Map.of());
        String payload;
        try {
            payload = json.writeValueAsString(envelope);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Account notification serialization failed", failure);
        }
        outbox.save(new AccountNotificationOutbox(eventId, recipientId, topic, payload, now));
    }

    /** Caller holds the attempt lock; no evidence or rejection reason leaves the KYC domain. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueKyc(com.hs.user.model.KycVerification attempt, KycEvent type) {
        if(attempt.getProvider()!=com.hs.user.model.constant.KycProvider.VNPT
                && !(attempt.getProvider()==com.hs.user.model.constant.KycProvider.ADMIN && type==KycEvent.KYC_VERIFIED)
                || type==KycEvent.KYC_VERIFIED && attempt.getStatus()!=com.hs.user.model.constant.KycStatus.VERIFIED
                || type==KycEvent.KYC_REJECTED && attempt.getStatus()!=com.hs.user.model.constant.KycStatus.REJECTED
                || type==KycEvent.KYC_RETRY_REQUIRED && attempt.getStatus()!=com.hs.user.model.constant.KycStatus.PENDING
                    && attempt.getStatus()!=com.hs.user.model.constant.KycStatus.REVIEW_REQUIRED)
            throw new IllegalStateException("KYC notification outcome mismatch");
        if(attempt.getId()==null || attempt.getId().isBlank())throw new IllegalStateException("KYC attempt missing");
        writeOnce(attempt.getUserId(),type.name(),attempt.getId(),Map.of("attemptId",attempt.getId()));
    }

    /** Natural membership expiry has no payment order; reuse the existing per-user durable publisher. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueMembershipExpiry(String ownerId, Instant expiry, MembershipExpiryEvent type) {
        if(expiry==null)throw new IllegalStateException("Membership expiry missing");
        writeOnce(ownerId,type.name(),expiry.toString(),Map.of());
    }

    private void writeOnce(String recipientId,String type,String source,Map<String,String> data) {
        if(recipientId==null || recipientId.isBlank())throw new IllegalStateException("Notification recipient missing");
        String eventId=UUID.nameUUIDFromBytes((type+":"+recipientId+":"+source).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        if(outbox.existsByEventId(eventId))return;
        Instant now=Instant.now();
        try {
            String payload=json.writeValueAsString(Map.of("eventId",eventId,"eventType",type,"version",1,
                    "occurredAt",TIMESTAMP.format(now),"recipients",List.of(recipientId),"data",data));
            outbox.save(new AccountNotificationOutbox(eventId,recipientId,topic,payload,now));
        } catch(JsonProcessingException failure) { throw new IllegalStateException("Notification serialization failed",failure); }
    }
}
