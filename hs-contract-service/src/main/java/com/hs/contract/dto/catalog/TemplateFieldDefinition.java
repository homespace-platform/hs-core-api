package com.hs.contract.dto.catalog;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private boolean required;
}
