package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.model.EthPaymentQuote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.Arrays;

@Component
public class EthPaymentSignatures {
    private static final String DOMAIN_TYPE = Hash.sha3String("EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)");
    private static final String QUOTE_TYPE = Hash.sha3String("PaymentQuote(bytes32 paymentId,address payer,address recipient,uint256 amountWei,uint256 expiresAt)");

    @Value("${homespace.eth-payment.quote-signer-private-key:}") private String privateKey;

    public String signerAddress() {
        return credentials().getAddress();
    }

    public String sign(EthPaymentQuote quote, String contract) {
        byte[] digest = digest(quote, contract);
        Sign.SignatureData signature = Sign.signMessage(digest, credentials().getEcKeyPair(), false);
        byte[] result = new byte[65];
        System.arraycopy(signature.getR(), 0, result, 0, 32);
        System.arraycopy(signature.getS(), 0, result, 32, 32);
        result[64] = signature.getV()[0];
        return Numeric.toHexString(result);
    }

    static byte[] digest(EthPaymentQuote quote, String contract) {
        String domain = hash(DOMAIN_TYPE, hashText("HomeSpaceEthPayment"), hashText("1"),
                uint(BigInteger.valueOf(11155111L)), addressWord(contract));
        String struct = hash(QUOTE_TYPE, word(quote.getPaymentId()), addressWord(quote.getPayerWallet()),
                addressWord(quote.getRecipientWallet()), uint(new BigInteger(quote.getAmountWei())),
                uint(BigInteger.valueOf(quote.getExpiresAt().getEpochSecond())));
        return Hash.sha3(Numeric.hexStringToByteArray("0x1901" + word(domain) + word(struct)));
    }

    public static boolean ownsWallet(String message, String signature, String wallet) {
        if (!EthPaymentChainClient.address(wallet) || signature == null || !signature.matches("0x[0-9a-fA-F]{130}")) return false;
        byte[] bytes = Numeric.hexStringToByteArray(signature);
        try {
            var sig = new Sign.SignatureData(bytes[64], Arrays.copyOfRange(bytes, 0, 32), Arrays.copyOfRange(bytes, 32, 64));
            String recovered = "0x" + Keys.getAddress(Sign.signedPrefixedMessageToKey(message.getBytes(StandardCharsets.UTF_8), sig));
            return recovered.equalsIgnoreCase(wallet);
        } catch (SignatureException | RuntimeException invalid) {
            return false;
        }
    }

    private Credentials credentials() {
        if (privateKey == null || !privateKey.matches("(?:0[xX])?[0-9a-fA-F]{64}"))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_CONFIGURED);
        try {
            BigInteger scalar = new BigInteger(Numeric.cleanHexPrefix(privateKey), 16);
            if (scalar.signum() <= 0 || scalar.compareTo(Sign.CURVE_PARAMS.getN()) >= 0)
                throw new IllegalArgumentException();
            return Credentials.create(privateKey);
        } catch (RuntimeException invalid) {
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_CONFIGURED);
        }
    }

    private static String hash(String... words) {
        return Numeric.toHexString(Hash.sha3(Numeric.hexStringToByteArray("0x" + String.join("", Arrays.stream(words).map(EthPaymentSignatures::word).toList()))));
    }

    private static String hashText(String text) {
        return Numeric.toHexString(Hash.sha3(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static String uint(BigInteger value) {
        if (value.signum() < 0 || value.bitLength() > 256) throw new IllegalArgumentException("Invalid uint256");
        return Numeric.toHexStringNoPrefixZeroPadded(value, 64);
    }

    private static String addressWord(String address) {
        if (!EthPaymentChainClient.address(address)) throw new IllegalArgumentException("Invalid address");
        return "0".repeat(24) + Numeric.cleanHexPrefix(address);
    }

    private static String word(String hex) {
        String value = Numeric.cleanHexPrefix(hex);
        if (!value.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Invalid bytes32");
        return value;
    }
}
