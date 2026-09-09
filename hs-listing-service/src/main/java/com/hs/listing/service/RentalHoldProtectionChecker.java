package com.hs.listing.service;

/**
 * Điểm mở rộng để module khác bảo vệ một yêu cầu thuê khỏi job hết hạn giữ chỗ.
 */
public interface RentalHoldProtectionChecker {

    boolean isProtected(String rentalRequestId);
}
