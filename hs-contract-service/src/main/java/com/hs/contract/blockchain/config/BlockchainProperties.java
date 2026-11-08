package com.hs.contract.blockchain.config;

import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "homespace.blockchain")
public class BlockchainProperties {
    private boolean enabled;
    private String rpcUrl = "";
    private long chainId = 11155111L;
    private String registryAddress = "";
    private String privateKey = "";
    private long pollDelayMs = 10000;
    private int rpcTimeoutSeconds = 15;
    private int retryDelaySeconds = 30;

    /** Chưa có khóa ví vẫn giữ job; worker sẽ thử lại sau khi cấu hình được bổ sung. */
    public boolean isConfigured() {
        return isRpcConfigured() && supportedChain(chainId) && validAddress(registryAddress);
    }

    public boolean isRpcConfigured() {
        try {
            URI uri = URI.create(rpcUrl);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && rpcTimeoutSeconds >= 1 && rpcTimeoutSeconds <= 60;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            return false;
        }
    }

    public static boolean supportedChain(long id) {
        return id == 11155111L || id == 31337L;
    }

    public static boolean validAddress(String value) {
        return value != null && value.matches("0x[0-9a-fA-F]{40}")
                && !value.equalsIgnoreCase("0x" + "0".repeat(40));
    }
}
