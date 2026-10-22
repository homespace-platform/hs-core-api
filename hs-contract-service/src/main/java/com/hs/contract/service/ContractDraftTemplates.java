package com.hs.contract.service;

import com.hs.contract.advice.ContractErrorCode;
import com.hs.contract.repository.ContractTemplateVersionRepository;
import com.hs.listing.model.constant.ListingCategory;

/** Same prerequisite for creation and the customer-facing onboarding explanation. */
public final class ContractDraftTemplates {
    private ContractDraftTemplates() {}

    public static ContractErrorCode problem(ContractTemplateVersionRepository versions, ListingCategory category) {
        if (category == null) return ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE;
        if (versions.countActiveSystemTemplates(category) > 1)
            return ContractErrorCode.SYSTEM_TEMPLATE_CONFIGURATION_INVALID;
        var published = versions.findCurrentPublishedSystemVersions(category);
        if (published.isEmpty()) return ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE;
        return published.size() == 1 ? null : ContractErrorCode.SYSTEM_TEMPLATE_CONFIGURATION_INVALID;
    }

    public static String message(ContractErrorCode problem) {
        return problem == ContractErrorCode.SYSTEM_TEMPLATE_NOT_AVAILABLE
            ? "Đã xác nhận đủ tiền nhưng chưa thể tạo bản nháp: chưa có mẫu hợp đồng hệ thống đã phát hành cho loại nhà này. Cần quản trị viên phát hành mẫu; hệ thống sẽ tự thử lại. Không chuyển tiền thêm."
            : "Đã xác nhận đủ tiền nhưng cấu hình mẫu hợp đồng bị trùng hoặc không hợp lệ. Cần quản trị viên kiểm tra mẫu; hệ thống sẽ tự thử lại. Không chuyển tiền thêm.";
    }
}
