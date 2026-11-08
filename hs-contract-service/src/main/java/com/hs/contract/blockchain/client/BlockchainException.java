package com.hs.contract.blockchain.client;

/** Chỉ chứa mã/thông báo an toàn, không giữ lỗi SDK/RPC có thể chứa private key hoặc API key. */
public class BlockchainException extends RuntimeException {
    private final String code;
    private final boolean terminal;

    public BlockchainException(String code, String message) {
        this(code, message, false);
    }

    public BlockchainException(String code, String message, boolean terminal) {
        super(message);
        this.code = code;
        this.terminal = terminal;
    }

    public String getCode() { return code; }
    public boolean isTerminal() { return terminal; }
}
