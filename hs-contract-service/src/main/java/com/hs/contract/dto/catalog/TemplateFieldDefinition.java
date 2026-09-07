package com.hs.contract.dto.catalog;

import com.hs.listing.model.constant.ListingCategory;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateFieldDefinition {
    private String key;
    private String label;
    private String group;
    private String dataType; // TEXT, NUMBER, DATE, DYNAMIC_TABLE
    private String description;
    private String example;
    /** Bắt buộc với ít nhất một loại hình bất động sản. */
    private boolean required;
    /** Các loại hình bất động sản mà trường này là bắt buộc. Rỗng nghĩa là luôn tùy chọn. */
    private Set<ListingCategory> requiredForCategories;
}
