package com.hs.contract.service;

import com.hs.contract.model.Contract;
import com.hs.listing.repository.ListingRepository;
import com.hs.listing.service.ListingOptionsService;
import com.hs.listing.service.ListingAmenitySnapshots;
import com.hs.listing.model.constant.ChargeMethodRules;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.*;

/** Candidates, not current contractual terms. Never expose branch identifiers or owner-only listing data. */
@Service @RequiredArgsConstructor
public class AmendmentOptionsService {
    private final ListingRepository listings;
    private final ListingOptionsService catalogs;
    public record Options(String listingId, String category, List<Map<String,Object>> amenities,
            List<Map<String,Object>> equipments, List<Map<String,Object>> furnishingCatalog,
            Map<String,List<String>> billingMethodsByChargeType) {}
    public Options get(Contract c, Map<String,Object> current) {
        var listing = listings.findById(c.getListingId()).orElseThrow(() -> AmendmentTermsBuilder.invalid("Không tìm thấy tài sản của hợp đồng."));
        List<Map<String,Object>> amenities = new ArrayList<>(), equipment = new ArrayList<>();
        ListingAmenitySnapshots.resolve(listing,listing.getBranch()).stream()
                .forEach(a -> amenities.add(amenity(a.getCode(),a.getName(),a.getSourceType())));
        listing.getFurnishings().forEach(f -> {
            Map<String,Object> row = new LinkedHashMap<>(); row.put("name",f.getAssetName()); row.put("quantity",f.getQuantity());
            row.put("condition",f.getHandoverCondition() == null ? "Theo thỏa thuận" : f.getHandoverCondition().label()); row.put("note",Objects.toString(f.getConditionNote(),""));
            equipment.removeIf(old -> f.getAssetName().equals(old.get("name"))); equipment.add(row);
        });
        var catalog = catalogs.getOptions(listing.getCategory()).furnishings().stream().map(f -> Map.<String,Object>of("code",f.code(),"name",f.name())).toList();
        return new Options(c.getListingId(),listing.getCategory().name(),amenities,equipment,catalog,ChargeMethodRules.all());
    }
    private Map<String,Object> amenity(String code,String name,String source) {
        var row = new LinkedHashMap<String,Object>();
        row.put("code",code); row.put("name",name); row.put("sourceType",source);
        return row;
    }
    @SuppressWarnings("unchecked")
    public void validate(Contract c, Map<String,Object> current, Map<String,Object> changes) {
        if (changes == null || !(changes.get("amenities") instanceof List<?> rows)) return;
        var allowed = new ArrayList<Map<String,Object>>((List<Map<String,Object>>)current.get("amenities"));
        allowed.addAll(get(c,current).amenities());
        for (Object value : rows) {
            if (!(value instanceof Map<?,?> row)) throw AmendmentTermsBuilder.invalid("Tiện ích không hợp lệ.");
            if (!Set.of("index","code","name","sourceType").containsAll(row.keySet()))
                throw AmendmentTermsBuilder.invalid("Tiện ích chỉ gồm tên; phí và chính sách nằm tại mục riêng.");
            if (allowed.stream().noneMatch(old -> Objects.equals(old.get("code"),row.get("code")) && Objects.equals(old.get("name"),row.get("name"))))
                throw AmendmentTermsBuilder.invalid("Chỉ được bổ sung tiện ích thuộc tài sản đang thuê.");
        }
    }
}
