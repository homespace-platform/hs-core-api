package com.hs.payment.service;

import com.hs.common.advice.entity.AppException;
import com.hs.payment.advice.PaymentErrorCode;
import com.hs.payment.model.EthPaymentQuote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

@Component
public class EthPaymentChainClient {
    private static final BigInteger SEPOLIA = BigInteger.valueOf(11155111L);
    static final String PAYMENT_RECEIVED = Hash.sha3String("PaymentReceived(bytes32,address,address,uint256)");
    private final Function<String, Web3j> web3Factory;

    @Value("${homespace.eth-payment.rpc-url:}") private String rpcUrl;
    @Value("${homespace.eth-payment.contract-address:}") private String contractAddress;
    @Value("${homespace.eth-payment.confirmations:3}") private int confirmations;

    public EthPaymentChainClient() {
        this(url -> Web3j.build(new HttpService(url)));
    }

    EthPaymentChainClient(Function<String, Web3j> web3Factory) {
        this.web3Factory = web3Factory;
    }

    public String contractAddress() {
        if (!address(contractAddress) || rpcUrl == null || rpcUrl.isBlank() || confirmations < 1)
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_CONFIGURED);
        try {
            URI uri = URI.create(rpcUrl);
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_CONFIGURED);
        }
        return contractAddress;
    }

    public void requireQuoteSigner(String signerAddress) {
        Web3j web3 = open();
        try {
            requireSepolia(web3);
            String call = Hash.sha3String("quoteSigner()").substring(0, 10);
            var result = web3.ethCall(Transaction.createEthCallTransaction(null, contractAddress, call),
                    DefaultBlockParameterName.LATEST).send();
            if (result.hasError() || result.getValue() == null || !result.getValue().matches("0x[0-9a-fA-F]{64}")
                    || !same("0x" + result.getValue().substring(26), signerAddress))
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_CONFIGURED);
        } catch (IOException unavailable) {
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_READY);
        } finally {
            web3.shutdown();
        }
    }

    public Instant verify(EthPaymentQuote quote, String txHash) {
        if (txHash == null || !txHash.matches("0x[0-9a-fA-F]{64}"))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        String expectedContract = quote.getContractAddress();
        if (!address(expectedContract)) throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
        Web3j web3 = open();
        try {
            requireSepolia(web3);
            var txResponse = web3.ethGetTransactionByHash(txHash).send();
            var receiptResponse = web3.ethGetTransactionReceipt(txHash).send();
            if (txResponse.hasError() || receiptResponse.hasError())
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_READY);
            var tx = txResponse.getTransaction().orElseThrow(() -> new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_READY));
            var receipt = receiptResponse.getTransactionReceipt().orElseThrow(() -> new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_READY));
            if (!receipt.isStatusOK() || !same(tx.getTo(), expectedContract) || !same(tx.getFrom(), quote.getPayerWallet())
                    || !same(receipt.getTransactionHash(), txHash) || !same(receipt.getTo(), expectedContract)
                    || !tx.getValue().equals(new BigInteger(quote.getAmountWei()))
                    || receipt.getBlockNumber() == null || tx.getBlockNumber() == null
                    || !receipt.getBlockNumber().equals(tx.getBlockNumber())
                    || receipt.getLogs() == null
                    || receipt.getLogs().stream().noneMatch(log -> matches(log, expectedContract, quote)))
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
            var head = web3.ethBlockNumber().send();
            if (head.hasError() || head.getBlockNumber() == null
                    || head.getBlockNumber().subtract(receipt.getBlockNumber()).add(BigInteger.ONE)
                    .compareTo(BigInteger.valueOf(confirmations)) < 0)
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_READY);
            var blockResponse = web3.ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.getBlockNumber()), false).send();
            var block = blockResponse.getBlock();
            if (blockResponse.hasError() || block == null || block.getTimestamp() == null
                    || block.getTimestamp().signum() < 0 || block.getTimestamp().compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0
                    || receipt.getBlockHash() == null || !same(block.getHash(), receipt.getBlockHash()))
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_READY);
            return Instant.ofEpochSecond(block.getTimestamp().longValueExact());
        } catch (IOException unavailable) {
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_NOT_READY);
        } finally {
            web3.shutdown();
        }
    }

    /** A finalized block after expiry makes every future use of this signature impossible. */
    public void requireFinalizedUnpaid(EthPaymentQuote quote) {
        if (!address(quote.getContractAddress()) || quote.getPaymentId() == null
                || !quote.getPaymentId().matches("0x[0-9a-fA-F]{64}"))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_REVIEW_REQUIRED);
        Web3j web3 = open();
        try {
            requireSepolia(web3);
            var finalized = web3.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send();
            if (finalized.hasError() || finalized.getBlock() == null
                    || finalized.getBlock().getNumber() == null || finalized.getBlock().getTimestamp() == null
                    || finalized.getBlock().getTimestamp().compareTo(BigInteger.valueOf(quote.getExpiresAt().getEpochSecond())) <= 0)
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_REVIEW_REQUIRED);
            String data = Hash.sha3String("paidPayments(bytes32)").substring(0, 10)
                    + Numeric.cleanHexPrefix(quote.getPaymentId());
            var paid = web3.ethCall(Transaction.createEthCallTransaction(null, quote.getContractAddress(), data),
                    DefaultBlockParameter.valueOf(finalized.getBlock().getNumber())).send();
            if (paid.hasError() || paid.getValue() == null || !paid.getValue().matches("0x[0-9a-fA-F]{64}")
                    || !BigInteger.ZERO.equals(Numeric.toBigInt(paid.getValue())))
                throw new AppException(PaymentErrorCode.ETH_PAYMENT_REVIEW_REQUIRED);
        } catch (IOException unavailable) {
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_REVIEW_REQUIRED);
        } finally {
            web3.shutdown();
        }
    }

    private Web3j open() {
        contractAddress();
        return web3Factory.apply(rpcUrl);
    }

    private static void requireSepolia(Web3j web3) throws IOException {
        var chain = web3.ethChainId().send();
        if (chain.hasError() || !SEPOLIA.equals(chain.getChainId()))
            throw new AppException(PaymentErrorCode.ETH_PAYMENT_INVALID);
    }

    static boolean matches(Log log, String contract, EthPaymentQuote quote) {
        if (log == null || log.isRemoved() || !same(log.getAddress(), contract) || log.getTopics() == null
                || log.getTopics().size() != 4 || !same(log.getTopics().get(0), PAYMENT_RECEIVED)
                || !same(log.getTopics().get(1), quote.getPaymentId())
                || !same(log.getTopics().get(2), topicAddress(quote.getPayerWallet()))
                || !same(log.getTopics().get(3), topicAddress(quote.getRecipientWallet()))
                || log.getData() == null || !log.getData().matches("0x[0-9a-fA-F]{64}")) return false;
        return Numeric.toBigInt(log.getData()).equals(new BigInteger(quote.getAmountWei()));
    }

    private static String topicAddress(String address) {
        return "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(address);
    }

    static boolean address(String value) {
        return value != null && value.matches("0x[0-9a-fA-F]{40}")
                && !value.equalsIgnoreCase("0x" + "0".repeat(40));
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }
}
