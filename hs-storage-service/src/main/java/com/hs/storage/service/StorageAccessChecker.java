package com.hs.storage.service;

import com.hs.storage.model.StorageObject;

/**
 * Cho phép module nghiệp vụ cấp quyền đọc file private theo reference của file.
 */
public interface StorageAccessChecker {

    boolean canAccess(String userId, StorageObject object);
}
