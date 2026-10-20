package com.hs.listing.model;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="rental_onboarding_events",indexes=@Index(name="idx_onboarding_event_rental",columnList="rental_request_id"))
@Getter @Builder @NoArgsConstructor @AllArgsConstructor
public class RentalOnboardingEvent {
    @Id @Builder.Default private String id=UUID.randomUUID().toString();
    @Column(name="rental_request_id",nullable=false) private String rentalRequestId;
    @Column(nullable=false) private Instant occurredAt;
    @Column(nullable=false) private String actorId;
    @Column(nullable=false) private String action;
    @Column(length=1200) private String note;
}
