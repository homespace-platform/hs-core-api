package com.hs.contract.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TemplateValidationResult {
    private boolean valid;
    
    @Builder.Default
    private List<String> detectedPlaceholders = new ArrayList<>();
    
    @Builder.Default
    private List<String> validPlaceholders = new ArrayList<>();
    
    @Builder.Default
    private List<String> invalidPlaceholders = new ArrayList<>();
    
    @Builder.Default
    private List<String> missingRequiredPlaceholders = new ArrayList<>();
    
    @Builder.Default
    private List<String> warnings = new ArrayList<>();
}
