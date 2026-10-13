package com.hs.storage.service;

import com.hs.storage.model.StorageObject;

/**
 * Cho phép module nghiệp vụ cấp quyền đọc file private theo reference của file.
 */
public interface StorageAccessChecker {

    boolean canAccess(String userId, StorageObject object);

    /** Business records can retain attached evidence even when its uploader owns the file. */
    default boolean preventsDeletion(StorageObject object) { return false; }
}
