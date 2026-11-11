package com.hs.payment.service;

import com.hs.payment.model.EthPaymentQuote;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECDSASignature;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class EthPaymentSignaturesTest {
    private static final String KEY = "0x" + "1".repeat(64);

    @Test
    void walletChallengeRequiresControlOfClaimedWallet() {
        Credentials wallet = Credentials.create(KEY);
        String message = "HomeSpace wallet ownership test";
        var signed = Sign.signPrefixedMessage(message.getBytes(StandardCharsets.UTF_8), wallet.getEcKeyPair());
        String signature = Numeric.toHexString(join(signed));
        assertTrue(EthPaymentSignatures.ownsWallet(message, signature, wallet.getAddress()));
        assertFalse(EthPaymentSignatures.ownsWallet(message + " changed", signature, wallet.getAddress()));
        assertFalse(EthPaymentSignatures.ownsWallet(message, signature, "0x" + "2".repeat(40)));
    }

    @Test
    void eip712SignatureRecoversConfiguredSignerAndBindsQuote() throws Exception {
        EthPaymentSignatures signer = new EthPaymentSignatures();
        ReflectionTestUtils.setField(signer, "privateKey", KEY);
        EthPaymentQuote quote = new EthPaymentQuote();
        quote.setPaymentId("0x" + "a".repeat(64));
        quote.setPayerWallet("0x" + "2".repeat(40));
        quote.setRecipientWallet("0x" + "3".repeat(40));
        quote.setAmountWei("123456789012345678");
        quote.setExpiresAt(Instant.ofEpochSecond(1794391800));
        String contract = "0x" + "4".repeat(40);
        byte[] signed = Numeric.hexStringToByteArray(signer.sign(quote, contract));
        BigInteger recovered = Sign.recoverFromSignature(signed[64] - 27,
                new ECDSASignature(new BigInteger(1, Arrays.copyOfRange(signed, 0, 32)),
                        new BigInteger(1, Arrays.copyOfRange(signed, 32, 64))),
                EthPaymentSignatures.digest(quote, contract));
        assertEquals(Credentials.create(KEY).getEcKeyPair().getPublicKey(), recovered);
        byte[] originalDigest = EthPaymentSignatures.digest(quote, contract);
        quote.setAmountWei("123456789012345679");
        assertFalse(Arrays.equals(originalDigest, EthPaymentSignatures.digest(quote, contract)));
    }

    private static byte[] join(Sign.SignatureData data) {
        byte[] result = new byte[65];
        System.arraycopy(data.getR(), 0, result, 0, 32);
        System.arraycopy(data.getS(), 0, result, 32, 32);
        result[64] = data.getV()[0];
        return result;
    }
}
