package com.hs.contract.service.engine;

import com.hs.listing.dto.response.ListingPolicyResponse;
import com.hs.listing.service.ListingPolicyService;
import java.util.*;

/** Canonical, frozen policy rows shared by UI, amendments and the Word data model. */
public final class ContractListingPolicies {
    private ContractListingPolicies() {}
    public static Map<String,Object> from(List<ListingPolicyResponse> rows) {
        var values = new ArrayList<Map<String,Object>>();
        for (var r : rows) {
            var value = new LinkedHashMap<String,Object>();
            value.put("code",r.code()); value.put("label",r.label()); value.put("valueType",r.valueType());
            value.put("value",r.value()); value.put("note",r.note()); value.put("displayValue",r.displayValue());
            value.put("unitLabel",r.unitLabel()); value.put("required",r.required()); value.put("minimumValue",r.minimumValue());
            value.put("maximumValue",r.maximumValue()); value.put("sortOrder",r.sortOrder()); values.add(value);
        }
        var result = new LinkedHashMap<String,Object>(); apply(result,values); return result;
    }
    public static void apply(Map<String,Object> target,List<Map<String,Object>> rows) {
        target.put("listingPolicies",rows);
        for (var row : rows) {
            String code = Objects.toString(row.get("code"),""), type = Objects.toString(row.get("valueType"),"");
            String value = Objects.toString(row.get("value"),"");
            String display = ListingPolicyService.display(type,value,Objects.toString(row.get("unitLabel"),""),Objects.toString(row.get("note"),""));
            row.put("displayValue",display);
            target.put(code,Set.of("noticeDaysBeforeTermination","depositRefundDays").contains(code) ? Integer.parseInt(value) : display);
            if (code.equals("noticeDaysBeforeTermination")) target.put("noticeDaysBeforeMoveOut",Integer.parseInt(value));
            String flag = switch(code) { case "petPolicy" -> "petsAllowed"; case "smokingPolicy" -> "smokingAllowed"; case "subleasePolicy" -> "sublettingAllowed"; default -> ""; };
            if (!flag.isEmpty()) target.put(flag,"APPROVAL_REQUIRED".equals(value) ? null : "ALLOWED".equals(value));
        }
    }
    @SuppressWarnings("unchecked")
    public static String text(Map<String,Object> policies) {
        if (!(policies.get("listingPolicies") instanceof List<?> source)) return "";
        return String.join("\n",source.stream().map(r -> { var row = (Map<String,Object>)r; return row.get("label") + ": " + row.get("displayValue"); }).toList());
    }
}
