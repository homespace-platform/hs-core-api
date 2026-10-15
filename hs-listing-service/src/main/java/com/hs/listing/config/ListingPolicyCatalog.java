package com.hs.listing.config;

import com.hs.listing.model.ListingPolicyDefinition;
import com.hs.listing.model.constant.ListingCategory;
import java.util.*;

/** Seed definitions only; runtime reads the database catalog, not frontend constants. */
public final class ListingPolicyCatalog {
    private ListingPolicyCatalog() {}
    public static List<ListingPolicyDefinition> defaults() {
        var all = Set.of(ListingCategory.HOUSE,ListingCategory.APARTMENT,ListingCategory.ROOM);
        return List.of(
            row("petPolicy","Nuôi thú cưng","PERMISSION",true,10,all,null,null,null,"Nêu rõ điều kiện nuôi nếu cho phép hoặc cần chấp thuận."),
            row("smokingPolicy","Hút thuốc","PERMISSION",true,20,all,null,null,null,"Nêu khu vực được phép, nếu có."),
            row("subleasePolicy","Cho thuê lại","PERMISSION",true,30,all,null,null,null,"Có cho người thuê chuyển giao hoặc cho thuê lại hay không?"),
            row("overnightGuestPolicy","Khách qua đêm","PERMISSION",true,40,all,null,null,null,"Nêu yêu cầu thông báo, đăng ký và điều kiện lưu trú."),
            row("propertyInspectionNotice","Thông báo trước khi kiểm tra tài sản","INTEGER",true,50,all,0,168,"giờ","Số giờ chủ nhà báo trước, không bao gồm tình huống khẩn cấp."),
            row("noticeDaysBeforeTermination","Báo trước khi kết thúc/chấm dứt thuê","INTEGER",true,60,all,0,180,"ngày","Điều kiện thỏa thuận của bài đăng, không phải gia hạn thanh toán."),
            row("depositRefundDays","Thời hạn hoàn cọc sau bàn giao và thanh toán đủ","INTEGER",true,70,all,1,60,"ngày","Chỉ bắt đầu sau khi hai bên hoàn tất bàn giao và các nghĩa vụ còn lại."),
            row("alterationsPolicy","Sửa chữa, cải tạo và lắp đặt","PERMISSION",true,80,Set.of(ListingCategory.HOUSE),null,null,null,"Nêu điều kiện bảo toàn kết cấu và hoàn trả hiện trạng."),
            row("buildingRulesPolicy","Nội quy tòa nhà","TEXT",true,80,Set.of(ListingCategory.APARTMENT),null,null,null,"Nhập nội quy áp dụng cho căn hộ này, không chỉ nhập tên tòa nhà."),
            row("sharedAmenitiesPolicy","Sử dụng tiện ích và không gian chung","TEXT",true,90,Set.of(ListingCategory.APARTMENT,ListingCategory.ROOM),null,null,null,"Quyền sử dụng, khung giờ, vệ sinh, điều kiện vận hành thực tế."),
            row("quietHoursPolicy","Giờ yên tĩnh và sinh hoạt","TEXT",true,100,Set.of(ListingCategory.ROOM),null,null,null,"Nêu giờ yên tĩnh và nội quy khu trọ; giờ ra vào vẫn theo chi tiết phòng.")
        );
    }
    private static ListingPolicyDefinition row(String code,String label,String type,boolean required,int order,
            Set<ListingCategory> categories,Integer min,Integer max,String unit,String help) {
        var r = new ListingPolicyDefinition(); r.setCode(code); r.setLabel(label); r.setValueType(type);
        r.setRequired(required); r.setSortOrder(order); r.setCategories(new HashSet<>(categories));
        r.setMinimumValue(min); r.setMaximumValue(max); r.setUnitLabel(unit); r.setHelpText(help); return r;
    }
}
