package com.hs.contract.dto.response;

/**
 * Một mã trường có vấn đề trong file Word mẫu: sai chính tả / không có trong từ điển,
 * hoặc là trường bắt buộc nhưng chưa được chèn.
 */
public record TemplateFieldIssue(String key, String label) {
}
