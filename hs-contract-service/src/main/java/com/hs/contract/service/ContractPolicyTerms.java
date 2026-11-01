package com.hs.contract.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Render only terms stored in this revision, not new defaults for historical agreements. */
public final class ContractPolicyTerms {
    private ContractPolicyTerms() {}

    public static String clause(Map<String, Object> policies) {
        if (policies == null || policies.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        days(lines, policies, "noticeDaysBeforeTermination", "Thời hạn báo trước khi chấm dứt: tối thiểu ", " ngày.");
        days(lines, policies, "noticeDaysBeforeMoveOut", "Thời hạn báo trước khi trả nhà/phòng: tối thiểu ", " ngày.");
        days(lines, policies, "depositRefundDays", "Thời hạn hoàn trả phần cọc còn lại sau quyết toán: trong vòng ", " ngày sau khi hoàn tất bàn giao.");
        text(lines, policies, "depositDeductionConditions", "Điều kiện khấu trừ tiền cọc");
        choice(lines, policies, "sublettingAllowed", "subleasePolicy", "Cho thuê lại",
                "Được phép theo thỏa thuận", "Không được phép");
        choice(lines, policies, "petsAllowed", "petPolicy", "Nuôi thú cưng",
                "Được phép, tuân thủ nội quy", "Không được phép");
        choice(lines, policies, "smokingAllowed", "smokingPolicy", "Hút thuốc",
                "Chỉ tại khu vực cho phép", "Không được phép");
        text(lines, policies, "overnightGuestPolicy", "Khách ở qua đêm");
        text(lines, policies, "propertyInspectionNotice", "Kiểm tra tài sản");
        text(lines, policies, "disputeResolution", "Giải quyết tranh chấp");
        text(lines, policies, "forceMajeure", "Bất khả kháng");
        return String.join("\n", lines);
    }

    private static void days(List<String> lines, Map<String, Object> source, String key, String prefix, String suffix) {
        Object value = source.get(key);
        if (value != null && String.valueOf(value).matches("\\d{1,4}")) lines.add(prefix + value + suffix);
    }

    private static void choice(List<String> lines, Map<String, Object> source, String flag, String key,
                               String label, String yes, String no) {
        if (source.get(flag) instanceof Boolean allowed) lines.add(label + ": " + (allowed ? yes : no) + ".");
        else text(lines, source, key, label);
    }

    private static void text(List<String> lines, Map<String, Object> source, String key, String label) {
        Object value = source.get(key);
        if (value instanceof String text && !text.isBlank()) lines.add(label + ": " + text.trim());
    }
}
