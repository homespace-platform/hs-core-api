package com.hs.listing.dto.response;

import com.hs.user.model.User;

public record ListingOwnerResponse(
        String id,
        String displayName,
        String phone,
        String avatarUrl
) {

    public static ListingOwnerResponse from(User user) {
        if (user == null) {
            return null;
        }
        String displayName = buildDisplayName(user);
        return new ListingOwnerResponse(
                user.getId(),
                displayName,
                user.getPhone(),
                user.getAvatarUrl());
    }

    private static String buildDisplayName(User user) {
        String fullName = String.join(
                        " ",
                        user.getFirstName() == null ? "" : user.getFirstName().trim(),
                        user.getLastName() == null ? "" : user.getLastName().trim())
                .trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return "Chủ nhà";
    }
}
