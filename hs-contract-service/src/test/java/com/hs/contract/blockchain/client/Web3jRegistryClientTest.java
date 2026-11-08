package com.hs.contract.blockchain.client;

import com.hs.contract.blockchain.config.BlockchainProperties;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.Web3jService;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.*;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Web3jRegistryClientTest {
    private static final String REGISTRY = "0x" + "1".repeat(40);
    private static final String RECORDER = "0x" + "2".repeat(40);
    private static final String ID = "0x" + "3".repeat(64);
    private static final String HASH = "0x" + "4".repeat(64);
    private static final String TX = "0x" + "5".repeat(64);
    private static final String BLOCK = "0x" + "6".repeat(64);
    BlockchainProperties properties;
    Web3jService service;
    Web3jRegistryClient client;

    @BeforeEach void setup() throws IOException {
        properties = new BlockchainProperties();
        properties.setRpcUrl("http://127.0.0.1:8545");
        service = mock(Web3jService.class);
        when(service.send(any(), any())).thenAnswer(invocation -> {
            Request<?, ?> request = invocation.getArgument(0);
            return switch (request.getMethod()) {
                case "eth_chainId" -> chain("0x7a69");
                case "eth_getCode" -> code();
                case "eth_call" -> stored(HASH, 100);
                default -> throw new AssertionError("Unexpected RPC method " + request.getMethod());
            };
        });
        client = new Web3jRegistryClient(properties, Web3j.build(service), null);
    }

    @Test void decodesExactStoredHashAndBlockTimestamp() {
        var result = client.readDocument(31337, REGISTRY, ID);
        assertEquals(HASH, result.documentHash());
        assertEquals(Instant.ofEpochSecond(100), result.registeredAt());
        assertTrue(result.exists());
    }

    @Test void wrongChainCannotReadOrSendToAnUnintendedNetwork() {
        BlockchainException failure = assertThrows(BlockchainException.class,
                () -> client.readDocument(11155111, REGISTRY, ID));
        assertEquals("WRONG_CHAIN", failure.getCode());
    }

    @Test void absentProofIsZeroHashAndDoesNotCountAsVerification() throws IOException {
        doReturn(stored("0x" + "0".repeat(64), 0)).when(service)
                .send(argThat(request -> request.getMethod().equals("eth_call")), any());
        assertFalse(client.readDocument(31337, REGISTRY, ID).exists());
    }

    @Test void providerErrorDoesNotExposeRpcUrlApiKeyOrCause() throws IOException {
        EthChainId response = new EthChainId();
        response.setError(new Response.Error(-32603, "https://example.invalid/secret-api-key password=secret"));
        doReturn(response).when(service).send(argThat(request -> request.getMethod().equals("eth_chainId")), any());
        BlockchainException failure = assertThrows(BlockchainException.class, () -> client.readDocument(31337, REGISTRY, ID));
        assertEquals("RPC_REJECTED", failure.getCode());
        assertFalse(failure.toString().contains("secret"));
        assertNull(failure.getCause());
    }

    @Test void receiptWithValidEventStillWaitsUntilFinalized() throws IOException {
        scriptReceipt(false, false);
        var receipt = client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX).orElseThrow();
        assertFalse(receipt.finalized());
        assertEquals(42, receipt.blockNumber());
        assertEquals(Instant.ofEpochSecond(100), receipt.registeredAt());
    }

    @Test void finalityAndCanonicalReceiptAreBothRequiredForSuccess() throws IOException {
        scriptReceipt(true, false);
        assertTrue(client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX).orElseThrow().finalized());
    }

    @Test void receiptFromAnOrphanedBlockIsTrackedAgainInsteadOfConfirmed() throws IOException {
        scriptReceipt(true, true);
        assertTrue(client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX).isEmpty());
    }

    @Test void wrongHashInEventCannotConfirmTheTransaction() throws IOException {
        scriptReceipt(true, false);
        TransactionReceipt receipt = transactionReceipt();
        receipt.getLogs().getFirst().setTopics(List.of(Hash.sha3String("DocumentRegistered(bytes32,bytes32,address,uint256)"), ID,
                "0x" + "a".repeat(64), "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(RECORDER)));
        EthGetTransactionReceipt response = new EthGetTransactionReceipt(); response.setResult(receipt);
        doReturn(response).when(service).send(argThat(request -> request.getMethod().equals("eth_getTransactionReceipt")), any());
        BlockchainException failure = assertThrows(BlockchainException.class,
                () -> client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX));
        assertEquals("INVALID_RECEIPT", failure.getCode());
    }

    @Test void missingFinalizedRpcDoesNotSilentlyDowngradeToLatest() throws IOException {
        scriptReceipt(true, false);
        EthBlock missing = new EthBlock(); missing.setResult(null);
        doReturn(missing).when(service).send(argThat(request -> request.getMethod().equals("eth_getBlockByNumber")
                && request.getParams().getFirst().equals("finalized")), any());
        assertEquals("FINALITY_UNAVAILABLE", assertThrows(BlockchainException.class,
                () -> client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX)).getCode());
    }

    @Test void revertedTransactionBecomesTerminalOnlyAfterCanonicalFinality() throws IOException {
        scriptReceipt(false, false);
        TransactionReceipt reverted = transactionReceipt(); reverted.setStatus("0x0"); reverted.setLogs(List.of());
        EthGetTransactionReceipt response = new EthGetTransactionReceipt(); response.setResult(reverted);
        doReturn(response).when(service).send(argThat(request -> request.getMethod().equals("eth_getTransactionReceipt")), any());
        BlockchainException pending = assertThrows(BlockchainException.class,
                () -> client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX));
        assertEquals("TRANSACTION_REVERT_PENDING_FINALITY", pending.getCode());
        assertFalse(pending.isTerminal());
        doReturn(block(45, "0xfinalized")).when(service).send(argThat(request -> request.getMethod().equals("eth_getBlockByNumber")
                && request.getParams().getFirst().equals("finalized")), any());
        BlockchainException terminal = assertThrows(BlockchainException.class,
                () -> client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX));
        assertEquals("TRANSACTION_REVERTED", terminal.getCode());
        assertTrue(terminal.isTerminal());
    }

    @Test void orphanedRevertDoesNotPermanentlyFailTheProof() throws IOException {
        scriptReceipt(true, true);
        TransactionReceipt reverted = transactionReceipt(); reverted.setStatus("0x0"); reverted.setLogs(List.of());
        EthGetTransactionReceipt response = new EthGetTransactionReceipt(); response.setResult(reverted);
        doReturn(response).when(service).send(argThat(request -> request.getMethod().equals("eth_getTransactionReceipt")), any());
        assertTrue(client.receipt(31337, REGISTRY, ID, HASH, RECORDER, TX).isEmpty());
    }

    @Test void privateKeyWithOrWithoutPrefixDerivesTheCorrectWalletOffline() throws Exception {
        var pair = Keys.createEcKeyPair();
        String hex = Numeric.toHexStringNoPrefixZeroPadded(pair.getPrivateKey(), 64);
        String expected = Credentials.create(pair).getAddress();
        for (String prefix : new String[] {"", "0x", "0X"}) {
            properties.setPrivateKey("  " + prefix + hex + "  ");
            client = new Web3jRegistryClient(properties, Web3j.build(service), null);
            assertEquals(expected, client.signerAddress());
        }
        verifyNoInteractions(service);
    }

    @Test void missingPrivateKeyIsRetryableAndDoesNotContactRpc() {
        for (String missing : new String[] {null, "", "   "}) {
            properties.setPrivateKey(missing);
            BlockchainException failure = assertThrows(BlockchainException.class, client::signerAddress);
            assertEquals("PRIVATE_KEY_NOT_CONFIGURED", failure.getCode());
            assertFalse(failure.isTerminal()); assertNull(failure.getCause());
        }
        verifyNoInteractions(service);
    }

    @Test void invalidFormatAndOutOfRangeScalarsNeverLeakTheInputOrCreateAWallet() {
        for (String invalid : new String[] {"secret-invalid-input", "1".repeat(63), "0x" + "g".repeat(64),
                "0".repeat(64), "f".repeat(64), Numeric.toHexStringNoPrefixZeroPadded(Sign.CURVE_PARAMS.getN(), 64)}) {
            properties.setPrivateKey(invalid);
            BlockchainException failure = assertThrows(BlockchainException.class, client::signerAddress);
            assertEquals("PRIVATE_KEY_INVALID", failure.getCode());
            assertFalse(failure.toString().contains(invalid)); assertNull(failure.getCause());
            assertFalse(failure.isTerminal());
        }
        verifyNoInteractions(service);
    }

    @Test void validPrivateKeyMustStillBelongToTheRegistryOwner() throws Exception {
        properties.setPrivateKey(Numeric.toHexStringNoPrefixZeroPadded(Keys.createEcKeyPair().getPrivateKey(), 64));
        doReturn(owner(RECORDER)).when(service).send(argThat(request -> request.getMethod().equals("eth_call")), any());
        BlockchainException failure = assertThrows(BlockchainException.class,
                () -> client.prepare(31337, REGISTRY, ID, HASH));
        assertEquals("OWNER_MISMATCH", failure.getCode());
        verify(service, never()).send(argThat(request -> request.getMethod().equals("eth_sendRawTransaction")), any());
    }

    @Test void offlinePreparationProducesTypeTwoTransactionWithoutBroadcast() throws Exception {
        Credentials credentials = Credentials.create(Keys.createEcKeyPair());
        properties.setPrivateKey(Numeric.toHexStringWithPrefixZeroPadded(credentials.getEcKeyPair().getPrivateKey(), 64));
        doAnswer(invocation -> {
            Request<?, ?> request = invocation.getArgument(0);
            return switch (request.getMethod()) {
                case "eth_chainId" -> chain("0x7a69");
                case "eth_getCode" -> code();
                case "eth_call" -> owner(credentials.getAddress());
                case "eth_getTransactionCount" -> { var result = new EthGetTransactionCount(); result.setResult("0x7"); yield result; }
                case "eth_maxPriorityFeePerGas" -> { var result = new EthMaxPriorityFeePerGas(); result.setResult("0x64"); yield result; }
                case "eth_getBlockByNumber" -> block(42, BLOCK);
                case "eth_estimateGas" -> { var result = new EthEstimateGas(); result.setResult("0x3ae1c"); yield result; }
                case "eth_getBalance" -> { var result = new EthGetBalance(); result.setResult("0xde0b6b3a7640000"); yield result; }
                default -> throw new AssertionError("Unexpected RPC method " + request.getMethod());
            };
        }).when(service).send(any(), any());
        var signed = client.prepare(31337, REGISTRY, ID, HASH);
        assertEquals("7", signed.nonce());
        assertEquals(credentials.getAddress(), signed.signerAddress());
        assertTrue(signed.rawTransaction().startsWith("0x02"));
        assertEquals(Hash.sha3(signed.rawTransaction()), signed.transactionHash());
        assertEquals("SignedSubmission[redacted]", signed.toString());
        verify(service, never()).send(argThat(request -> request.getMethod().equals("eth_sendRawTransaction")), any());
    }

    private void scriptReceipt(boolean finalized, boolean orphaned) throws IOException {
        doAnswer(invocation -> {
            Request<?, ?> request = invocation.getArgument(0);
            return switch (request.getMethod()) {
                case "eth_chainId" -> chain("0x7a69");
                case "eth_getCode" -> code();
                case "eth_call" -> stored(HASH, 100);
                case "eth_getTransactionReceipt" -> { var response = new EthGetTransactionReceipt(); response.setResult(transactionReceipt()); yield response; }
                case "eth_getBlockByNumber" -> request.getParams().getFirst().equals("finalized")
                        ? block(finalized ? 45 : 41, "0xfinalized") : block(42, orphaned ? "0xother-block" : BLOCK);
                default -> throw new AssertionError("Unexpected RPC method " + request.getMethod());
            };
        }).when(service).send(any(), any());
    }

    private static TransactionReceipt transactionReceipt() {
        Log event = new Log(); event.setAddress(REGISTRY); event.setRemoved(false);
        event.setTopics(List.of(Hash.sha3String("DocumentRegistered(bytes32,bytes32,address,uint256)"), ID, HASH,
                "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(RECORDER)));
        event.setData(Numeric.toHexStringWithPrefixZeroPadded(BigInteger.valueOf(100), 64));
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1"); receipt.setBlockNumber("0x2a"); receipt.setBlockHash(BLOCK);
        receipt.setTransactionHash(TX); receipt.setFrom(RECORDER); receipt.setTo(REGISTRY); receipt.setLogs(List.of(event));
        return receipt;
    }

    private static EthChainId chain(String chainId) { var response = new EthChainId(); response.setResult(chainId); return response; }
    private static EthGetCode code() { var response = new EthGetCode(); response.setResult("0x6000"); return response; }
    private static EthCall stored(String hash, long timestamp) {
        EthCall response = new EthCall();
        response.setResult("0x" + FunctionEncoder.encodeConstructor(List.of(new Bytes32(Numeric.hexStringToByteArray(hash)), new Uint256(timestamp))));
        return response;
    }
    private static EthCall owner(String address) {
        EthCall response = new EthCall(); response.setResult("0x" + FunctionEncoder.encodeConstructor(List.of(new Address(address)))); return response;
    }
    private static EthBlock block(long number, String hash) {
        EthBlock.Block block = new EthBlock.Block();
        block.setNumber(Numeric.encodeQuantity(BigInteger.valueOf(number))); block.setHash(hash);
        block.setTimestamp("0x64"); block.setBaseFeePerGas("0x64");
        EthBlock response = new EthBlock(); response.setResult(block); return response;
    }
}
