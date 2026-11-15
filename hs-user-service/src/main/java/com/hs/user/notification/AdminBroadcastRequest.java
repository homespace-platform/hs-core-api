package com.hs.user.notification;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminBroadcastRequest(
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}") String broadcastId,
        @NotBlank @Size(max = 200) @Pattern(regexp = "[^<>\\x00-\\x1f\\x7f]+") String title,
        @NotBlank @Size(max = 2000) @Pattern(regexp = "[^<>\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]+") String body,
        @NotNull Priority priority,
        @Size(max = 500) String actionPath) {
    public enum Priority { NORMAL, HIGH, URGENT }

    @AssertTrue(message = "actionPath must be a safe relative path")
    public boolean isValidActionPath() {
        return actionPath == null || (actionPath.matches("^/(?!/)[A-Za-z0-9\\-._~!$&'()*+,;=:@%/?#]*$")
                && !actionPath.contains("://") && !java.util.regex.Pattern.compile("(^|/)\\.\\.(/|$|\\?|#)").matcher(actionPath).find());
    }
}
