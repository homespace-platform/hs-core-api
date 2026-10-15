package com.hs.listing.service;

import com.hs.common.advice.entity.AppException;
import com.hs.listing.advice.ListingErrorCode;
import com.hs.listing.config.ListingPolicyCatalog;
import com.hs.listing.dto.request.ListingPolicyRequest;
import com.hs.listing.dto.response.*;
import com.hs.listing.model.*;
import com.hs.listing.model.constant.ListingCategory;
import com.hs.listing.repository.ListingPolicyDefinitionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor
public class ListingPolicyService {
    private final ListingPolicyDefinitionRepository definitions;
    public static final List<ListingPolicyOption.Choice> PERMISSIONS = List.of(
            new ListingPolicyOption.Choice("ALLOWED","Cho phép"),new ListingPolicyOption.Choice("NOT_ALLOWED","Không cho phép"),
            new ListingPolicyOption.Choice("APPROVAL_REQUIRED","Cần chủ nhà chấp thuận trước"));
    public List<ListingPolicyDefinition> definitions(ListingCategory category) {
        // The null repository is used only by compatibility constructors in standalone unit fixtures.
        var catalog = definitions == null ? ListingPolicyCatalog.defaults() : definitions.findAllByActiveTrueOrderBySortOrderAsc();
        return catalog.stream().filter(d -> d.isActive() && d.getCategories().contains(category) && !reserved(d.getCode())).toList();
    }
    public List<ListingPolicyOption> options(ListingCategory category) {
        return definitions(category).stream().map(d -> new ListingPolicyOption(d.getCode(),d.getLabel(),d.getValueType(),d.getHelpText(),d.getUnitLabel(),
                d.isRequired(),d.getMinimumValue(),d.getMaximumValue(),d.getSortOrder(),"PERMISSION".equals(d.getValueType()) ? PERMISSIONS : List.of())).toList();
    }
    public void attach(Listing listing,List<ListingPolicyRequest> input,boolean publishing) {
        var catalog = definitions(listing.getCategory());
        if (publishing && catalog.isEmpty()) throw invalid("Chưa cấu hình danh mục chính sách cho loại hình này.");
        var values = input == null ? List.<ListingPolicyRequest>of() : input;
        if (values.size() > 50) throw invalid("Tối đa 50 chính sách.");
        var seen = new HashSet<String>(); var rows = new ArrayList<ListingPolicyValue>();
        for (var value : values) {
            if (value == null || !seen.add(value.code())) throw invalid("Chính sách bị trùng hoặc không hợp lệ.");
            var d = catalog.stream().filter(c -> c.getCode().equals(value.code())).findFirst().orElseThrow(() -> invalid("Chính sách không áp dụng cho loại hình: " + value.code()));
            String normalized = validateValue(d.getValueType(),value.value(),d.getMinimumValue(),d.getMaximumValue());
            if (value.note() != null && value.note().length() > 1000) throw invalid("Ghi chú chính sách tối đa 1000 ký tự.");
            var row = new ListingPolicyValue(); row.setListing(listing); row.setDefinition(d); row.setValue(normalized); row.setNote(value.note() == null ? "" : value.note().trim()); rows.add(row);
        }
        if (publishing) for (var d : catalog) if (d.isRequired() && !seen.contains(d.getCode())) throw invalid("Cần nhập chính sách: " + d.getLabel());
        listing.getPolicies().clear(); listing.getPolicies().addAll(rows);
    }
    public static String validateValue(String type,String value,Integer min,Integer max) {
        if (value == null || value.trim().isEmpty() || value.length() > 2000) throw invalid("Cần nhập giá trị chính sách hợp lệ.");
        String v = value.trim();
        if ("PERMISSION".equals(type)) {
            if (PERMISSIONS.stream().noneMatch(c -> c.value().equals(v))) throw invalid("Lựa chọn cho phép không hợp lệ.");
        } else if ("INTEGER".equals(type)) {
            try { int n = new BigDecimal(v).intValueExact(); if ((min != null && n < min) || (max != null && n > max)) throw new NumberFormatException(); return String.valueOf(n); }
            catch (ArithmeticException | NumberFormatException e) { throw invalid("Số ngày/giờ chính sách không hợp lệ."); }
        } else if (!"TEXT".equals(type)) throw invalid("Loại chính sách chưa được hỗ trợ.");
        return v;
    }
    public static String display(String type,String value,String unit,String note) {
        String text = "PERMISSION".equals(type) ? PERMISSIONS.stream().filter(c -> c.value().equals(value)).map(ListingPolicyOption.Choice::label).findFirst().orElse("Chưa xác định")
                : value + ("INTEGER".equals(type) && unit != null && !unit.isBlank() ? " " + unit : "");
        return text + (note != null && !note.isBlank() ? " — " + note.trim() : "");
    }
    public static List<ListingPolicyResponse> snapshot(Listing listing) {
        if (listing == null || listing.getPolicies() == null) return List.of();
        return listing.getPolicies().stream().sorted(Comparator.comparingInt(v -> v.getDefinition().getSortOrder())).map(v -> {
            var d = v.getDefinition(); return new ListingPolicyResponse(d.getCode(),d.getLabel(),d.getValueType(),v.getValue(),v.getNote(),
                    display(d.getValueType(),v.getValue(),d.getUnitLabel(),v.getNote()),d.getUnitLabel(),d.isRequired(),d.getMinimumValue(),d.getMaximumValue(),d.getSortOrder());
        }).toList();
    }
    private static AppException invalid(String message) { return new AppException(ListingErrorCode.RENTAL_ESTIMATE_INVALID,message); }
    private static boolean reserved(String code) {
        return code == null || code.startsWith("latePayment") || Set.of("listingPolicies","paymentDueDay","paymentCycle","depositAmount","bankAccountNumber","bankAccountHolder","overdueLandlordTerminationAfterFiveDays").contains(code);
    }
}
