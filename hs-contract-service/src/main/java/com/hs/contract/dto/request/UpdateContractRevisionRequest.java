package com.hs.contract.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateContractRevisionRequest {

    public record LandlordContact(String phone, String email) {}
    public record DraftContent(Map<String, Object> landlord, Map<String, Object> tenant, Map<String, Object> property,
                               List<Map<String, Object>> equipments, List<Map<String, Object>> amenities) {}

    /** Optional for legacy clients; new editors send the revision they actually reviewed. */
    private String baseRevisionId;
    private String listingSourceVersion;

    private Map<String, Object> landlord;

    private LandlordContact landlordContact;
    private DraftContent draftContent;

    private Map<String, Object> tenant;

    private Map<String, Object> property;

    private Map<String, Object> lease;

    private Map<String, Object> financial;

    private List<Map<String, Object>> charges;

    private List<Map<String, Object>> equipments;

    private Map<String, Object> meters;

    private Map<String, Object> initialPayment;

    private List<Map<String, Object>> amenities;

    private Map<String, Object> policies;

    private String specialTerms;

    /** Editable text from the draft review form, separate from system terms. */
    private String additionalTerms;

    private String revisionNote;
}
