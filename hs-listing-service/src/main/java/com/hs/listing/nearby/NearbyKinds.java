package com.hs.listing.nearby;

import java.util.*;
import static com.hs.listing.nearby.NearbyTypes.Group;

/**
 * Specific place types a renter may ask for. Each kind is one requested category ID whose meaning is
 * approved in the category registry; matching uses the stored verified parent graph of the source, never
 * the POI name. Sub-IDs below these roots are not exposed: their meaning is not verified.
 */
public final class NearbyKinds {
 private NearbyKinds() {}
 public record Kind(String code, String categoryId, Group group, String label, String note) {}
 public static final List<Kind> ALL = List.of(
  new Kind("SCHOOL", "7372", Group.EDUCATION, "Trường học/cơ sở giáo dục", "Nguồn gộp mầm non, phổ thông, trung tâm ngoại ngữ; chưa tách riêng cấp học."),
  new Kind("COLLEGE_UNIVERSITY", "7377", Group.EDUCATION, "Cao đẳng/đại học", null),
  new Kind("HOSPITAL_POLYCLINIC", "7321", Group.HEALTHCARE, "Bệnh viện/phòng khám đa khoa", "Nguồn xếp chung bệnh viện và phòng khám; không thay bằng nhà thuốc."),
  new Kind("PHARMACY", "7326", Group.HEALTHCARE, "Nhà thuốc", null),
  new Kind("SUPERMARKET", "7332005", Group.SHOPPING, "Siêu thị", "Không gồm cửa hàng tiện lợi."),
  new Kind("CONVENIENCE_STORE", "9361009", Group.SHOPPING, "Cửa hàng tiện lợi", null),
  new Kind("RESTAURANT", "7315", Group.FOOD_DRINK, "Quán ăn/nhà hàng", null),
  new Kind("BANK", "7328", Group.FINANCE, "Ngân hàng", null),
  new Kind("BUS_STOP", "9942002", Group.TRANSPORT, "Trạm xe buýt", null),
  new Kind("PARK_RECREATION", "9362", Group.RECREATION, "Công viên/khu vui chơi", null));

 /** Kinds available for the configured scan scope only; another scope cannot reuse these meanings. */
 public static List<Kind> available(Set<String> requestedCategoryIds) {
  return ALL.stream().filter(k -> requestedCategoryIds.contains(k.categoryId())).toList();
 }

 public static Optional<Kind> find(String code) {
  return ALL.stream().filter(k -> k.code().equals(code)).findFirst();
 }
}
