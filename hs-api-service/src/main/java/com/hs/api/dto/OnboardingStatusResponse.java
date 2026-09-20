package com.hs.api.dto;

import lombok.Builder;
import java.util.List;

@Builder
public record OnboardingStatusResponse(
        boolean completed,
        int version,
        List<String> requiredSteps,
        List<String> completedSteps,
        String nextStep
) {
}
