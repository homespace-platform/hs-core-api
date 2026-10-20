package com.hs.listing.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Append-only decisions: never replace an earlier request when the tenant asks again. */
@Entity
@Table(name="rental_start_changes", indexes=@Index(name="idx_start_change_rental",columnList="rental_request_id"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RentalStartChange {
    @Id @Builder.Default private String id=UUID.randomUUID().toString();
    @Column(name="rental_request_id",nullable=false) private String rentalRequestId;
    @Column(nullable=false) private LocalDate previousDate;
    @Column(nullable=false) private LocalDate requestedDate;
    @Column(nullable=false,length=1000) private String reason;
    @Column(nullable=false) private String status;
    @Column(nullable=false) private Instant requestedAt;
    @Column(nullable=false) private Instant respondBy;
    private Instant decidedAt;
    private String decidedBy;
    @Column(length=1000) private String responseNote;
}
