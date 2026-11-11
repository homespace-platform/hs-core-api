package com.hs.payment.service;

import com.hs.payment.model.EthPaymentQuote;
import com.hs.common.advice.entity.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.List;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EthPaymentChainClientTest {
    @Test
    void eventMustMatchContractPaymentPayerRecipientAndExactWei() {
        EthPaymentQuote quote = new EthPaymentQuote();
        quote.setPaymentId("0x" + "a".repeat(64));
        quote.setPayerWallet("0x" + "1".repeat(40));
        quote.setRecipientWallet("0x" + "2".repeat(40));
        quote.setAmountWei("123456789");
        String contract = "0x" + "3".repeat(40);
        Log log = new Log();
        log.setAddress(contract);
        log.setTopics(List.of(EthPaymentChainClient.PAYMENT_RECEIVED, quote.getPaymentId(),
                topic(quote.getPayerWallet()), topic(quote.getRecipientWallet())));
        log.setData("0x" + Numeric.toHexStringNoPrefixZeroPadded(new BigInteger(quote.getAmountWei()), 64));
        assertTrue(EthPaymentChainClient.matches(log, contract, quote));
        assertFalse(EthPaymentChainClient.matches(log, "0x" + "4".repeat(40), quote));
        quote.setAmountWei("123456790");
        assertFalse(EthPaymentChainClient.matches(log, contract, quote));
    }

    private static String topic(String address) {
        return "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(address);
    }

    @Test
    void verifiedReceiptReturnsCanonicalMinedTimeForUpgradeEligibility() throws Exception {
        Web3j web3 = mock(Web3j.class, RETURNS_DEEP_STUBS);
        EthPaymentChainClient client = new EthPaymentChainClient(url -> web3);
        String contract = "0x" + "3".repeat(40);
        String payer = "0x" + "1".repeat(40);
        String recipient = "0x" + "2".repeat(40);
        String txHash = "0x" + "4".repeat(64);
        String blockHash = "0x" + "5".repeat(64);
        ReflectionTestUtils.setField(client, "rpcUrl", "https://sepolia.example.test");
        ReflectionTestUtils.setField(client, "contractAddress", contract);
        ReflectionTestUtils.setField(client, "confirmations", 3);
        EthPaymentQuote quote = new EthPaymentQuote();
        quote.setPaymentId("0x" + "a".repeat(64));
        quote.setPayerWallet(payer);
        quote.setRecipientWallet(recipient);
        quote.setContractAddress(contract);
        quote.setAmountWei("123");
        var tx = mock(org.web3j.protocol.core.methods.response.Transaction.class);
        var receipt = mock(TransactionReceipt.class);
        var block = mock(EthBlock.Block.class);
        Log log = new Log();
        log.setAddress(contract);
        log.setTopics(List.of(EthPaymentChainClient.PAYMENT_RECEIVED, quote.getPaymentId(), topic(payer), topic(recipient)));
        log.setData("0x" + Numeric.toHexStringNoPrefixZeroPadded(BigInteger.valueOf(123), 64));
        when(web3.ethChainId().send().getChainId()).thenReturn(BigInteger.valueOf(11155111L));
        when(web3.ethGetTransactionByHash(txHash).send().getTransaction()).thenReturn(java.util.Optional.of(tx));
        when(web3.ethGetTransactionReceipt(txHash).send().getTransactionReceipt()).thenReturn(java.util.Optional.of(receipt));
        when(tx.getTo()).thenReturn(contract);
        when(tx.getFrom()).thenReturn(payer);
        when(tx.getValue()).thenReturn(BigInteger.valueOf(123));
        when(tx.getBlockNumber()).thenReturn(BigInteger.TEN);
        when(receipt.isStatusOK()).thenReturn(true);
        when(receipt.getTransactionHash()).thenReturn(txHash);
        when(receipt.getTo()).thenReturn(contract);
        when(receipt.getBlockNumber()).thenReturn(BigInteger.TEN);
        when(receipt.getBlockHash()).thenReturn(blockHash);
        when(receipt.getLogs()).thenReturn(List.of(log));
        when(web3.ethBlockNumber().send().getBlockNumber()).thenReturn(BigInteger.valueOf(12));
        when(web3.ethGetBlockByNumber(any(DefaultBlockParameter.class), eq(false)).send().getBlock()).thenReturn(block);
        when(block.getHash()).thenReturn(blockHash);
        when(block.getTimestamp()).thenReturn(BigInteger.valueOf(1_700_000_000));

        assertEquals(Instant.ofEpochSecond(1_700_000_000), client.verify(quote, txHash));
        when(block.getHash()).thenReturn("0x" + "6".repeat(64));
        assertThrows(AppException.class, () -> client.verify(quote, txHash));
    }

    @Test
    void renewalRequiresFinalizedBlockAfterExpiryAndUnpaidContractState() throws Exception {
        Web3j web3 = mock(Web3j.class, RETURNS_DEEP_STUBS);
        EthPaymentChainClient client = new EthPaymentChainClient(url -> web3);
        String contract = "0x" + "3".repeat(40);
        ReflectionTestUtils.setField(client, "rpcUrl", "https://sepolia.example.test");
        ReflectionTestUtils.setField(client, "contractAddress", contract);
        ReflectionTestUtils.setField(client, "confirmations", 3);
        EthPaymentQuote old = new EthPaymentQuote();
        old.setPaymentId("0x" + "a".repeat(64));
        old.setContractAddress(contract);
        old.setExpiresAt(Instant.ofEpochSecond(100));
        when(web3.ethChainId().send().getChainId()).thenReturn(BigInteger.valueOf(11155111L));
        when(web3.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send().getBlock().getNumber())
                .thenReturn(BigInteger.TEN);
        AtomicReference<BigInteger> finalizedTime = new AtomicReference<>(BigInteger.valueOf(100));
        when(web3.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send().getBlock().getTimestamp())
                .thenAnswer(inv -> finalizedTime.get());
        AtomicReference<String> paidValue = new AtomicReference<>("0x" + "0".repeat(63) + "1");
        when(web3.ethCall(any(Transaction.class), any(DefaultBlockParameter.class)).send().getValue())
                .thenAnswer(inv -> paidValue.get());

        assertThrows(AppException.class, () -> client.requireFinalizedUnpaid(old));
        finalizedTime.set(BigInteger.valueOf(101));
        assertThrows(AppException.class, () -> client.requireFinalizedUnpaid(old));
        paidValue.set("0x" + "0".repeat(64));
        assertDoesNotThrow(() -> client.requireFinalizedUnpaid(old));
        when(web3.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send().hasError()).thenReturn(true);
        assertThrows(AppException.class, () -> client.requireFinalizedUnpaid(old));
        var block = org.mockito.ArgumentCaptor.forClass(DefaultBlockParameter.class);
        verify(web3, times(2)).ethCall(any(Transaction.class), block.capture());
        assertTrue(block.getAllValues().stream().allMatch(value ->
                value.getValue().equals(DefaultBlockParameter.valueOf(BigInteger.TEN).getValue())));
    }
}
