package com.hs.contract.blockchain.persistence;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class DocumentProofHash {
    private DocumentProofHash() {}

    /** Băm đúng bytes của PDF đã ký; không băm URL hoặc JSON nội dung hợp đồng. */
    public static String sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "PDF bytes are required");
        try {
            return "0x" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
