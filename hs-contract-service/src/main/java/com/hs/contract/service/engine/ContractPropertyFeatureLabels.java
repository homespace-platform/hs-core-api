package com.hs.contract.service.engine;

import java.util.Locale;
import java.util.Map;

/** Presentation only. Never change enum codes in Listing or rewrite signed snapshots. */
public final class ContractPropertyFeatureLabels {
    private ContractPropertyFeatureLabels() {}

    public static String value(String featureName, Object raw) {
        String original = raw == null ? "" : raw.toString();
        String key = original.trim().toUpperCase(Locale.ROOT);
        String field = featureName == null ? "" : featureName.trim().toLowerCase(Locale.ROOT);
        Map<String,String> labels = switch (field) {
            case "tình trạng nội thất" -> Map.of(
                    "UNFURNISHED","Không nội thất", "BASIC","Nội thất cơ bản",
                    "PARTIALLY_FURNISHED","Nội thất một phần", "FULLY_FURNISHED","Đầy đủ nội thất", "LUXURY","Nội thất cao cấp");
            case "loại lối đi", "lối đi" -> Map.of(
                    "PRIVATE","Riêng biệt", "SHARED","Dùng chung", "ĐƯỜNG NỘI BỘ","Đường nội bộ",
                    "HẺM XE HƠI","Hẻm xe hơi", "HẺM XE MÁY","Hẻm xe máy", "MẶT TIỀN","Mặt tiền");
            case "nhà vệ sinh", "khu vực bếp", "ban công", "đồng hồ điện", "đồng hồ nước" -> Map.of(
                    "PRIVATE","Riêng", "SHARED","Dùng chung", "NONE","Không có");
            case "giờ giấc sinh hoạt" -> Map.of("FLEXIBLE","Tự do giờ giấc", "FREE","Tự do giờ giấc", "CURFEW","Có giờ đóng cửa");
            case "chính sách gửi xe" -> Map.of("NONE","Không có chỗ gửi xe", "FREE","Miễn phí", "PAID","Có thu phí");
            case "hướng cửa chính", "hướng ban công" -> Map.ofEntries(
                    Map.entry("EAST","Đông"), Map.entry("WEST","Tây"), Map.entry("SOUTH","Nam"), Map.entry("NORTH","Bắc"),
                    Map.entry("SOUTH_EAST","Đông Nam"), Map.entry("SOUTHEAST","Đông Nam"),
                    Map.entry("NORTH_EAST","Đông Bắc"), Map.entry("NORTHEAST","Đông Bắc"),
                    Map.entry("SOUTH_WEST","Tây Nam"), Map.entry("SOUTHWEST","Tây Nam"),
                    Map.entry("NORTH_WEST","Tây Bắc"), Map.entry("NORTHWEST","Tây Bắc"));
            case "tình trạng pháp lý", "pháp lý", "giấy tờ pháp lý" -> Map.of(
                    "NONE","Chưa cập nhật", "PINK_BOOK","Sổ hồng", "RED_BOOK","Sổ đỏ",
                    "CONTRACT","Hợp đồng mua bán", "PENDING","Đang chờ cấp giấy tờ", "OTHER","Khác");
            default -> Map.of();
        };
        // Free-entered descriptions, unfamiliar codes, names and abbreviations stay verbatim.
        return labels.getOrDefault(key, original).replace("m²", "mét vuông");
    }
}
