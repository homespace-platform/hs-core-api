package com.hs.contract.blockchain.client;

import com.hs.contract.blockchain.config.BlockchainProperties;
import jakarta.annotation.PreDestroy;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.Sign;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

@Component
public class Web3jRegistryClient implements RegistryClient {
    private static final String EVENT = Hash.sha3String("DocumentRegistered(bytes32,bytes32,address,uint256)");
    private final BlockchainProperties properties;
    private Web3j web3;
    private Credentials credentials;

    @Autowired
    public Web3jRegistryClient(BlockchainProperties properties) {
        this.properties = properties;
    }

    Web3jRegistryClient(BlockchainProperties properties, Web3j web3, Credentials credentials) {
        this.properties = properties;
        this.web3 = web3;
        this.credentials = credentials;
    }

    @Override
    public ChainProof readDocument(long chainId, String registryAddress, String onChainDocumentId) {
        requireNetwork(chainId, registryAddress);
        Function function = new Function("documents", List.of(bytes32(onChainDocumentId)),
                List.of(new TypeReference<Bytes32>() {}, new TypeReference<Uint256>() {}));
        List<Type> result = call(registryAddress, function);
        if (result.size() != 2) throw incompatible();
        return new ChainProof(Numeric.toHexString((byte[]) result.get(0).getValue()),
                Instant.ofEpochSecond(((BigInteger) result.get(1).getValue()).longValueExact()));
    }

    @Override
    public String signerAddress() { return signer().getAddress().toLowerCase(Locale.ROOT); }

    @Override
    public SignedSubmission prepare(long chainId, String registryAddress, String onChainDocumentId, String documentHash) {
        requireNetwork(chainId, registryAddress);
        Credentials signer = signer();
        requireOwner(registryAddress, signer.getAddress());
        Function function = new Function("registerDocument", List.of(bytes32(onChainDocumentId), bytes32(documentHash)), List.of());
        String data = FunctionEncoder.encode(function);
        BigInteger nonce = rpc(node().ethGetTransactionCount(signer.getAddress(), DefaultBlockParameterName.PENDING)).getTransactionCount();
        BigInteger priority = rpc(node().ethMaxPriorityFeePerGas()).getMaxPriorityFeePerGas().max(BigInteger.ONE);
        EthBlock.Block head = rpc(node().ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)).getBlock();
        if (head == null || head.getBaseFeePerGas() == null) throw incompatible();
        BigInteger maxFee = head.getBaseFeePerGas().multiply(BigInteger.TWO).add(priority);
        var estimate = org.web3j.protocol.core.methods.request.Transaction.createFunctionCallTransaction(
                signer.getAddress(), nonce, maxFee, null, registryAddress, BigInteger.ZERO, data);
        BigInteger gas = rpc(node().ethEstimateGas(estimate)).getAmountUsed().multiply(BigInteger.valueOf(12))
                .divide(BigInteger.TEN).add(BigInteger.valueOf(10000));
        if (gas.signum() <= 0 || gas.compareTo(BigInteger.valueOf(1000000)) > 0) {
            throw new BlockchainException("UNEXPECTED_GAS", "Ước tính gas bất thường; kiểm tra đúng registry.", true);
        }
        BigInteger balance = rpc(node().ethGetBalance(signer.getAddress(), DefaultBlockParameterName.PENDING)).getBalance();
        if (balance.compareTo(gas.multiply(maxFee)) < 0) {
            throw new BlockchainException("INSUFFICIENT_GAS", "Ví hệ thống chưa đủ ETH thử nghiệm để trả gas.");
        }
        RawTransaction transaction = RawTransaction.createTransaction(chainId, nonce, gas, registryAddress,
                BigInteger.ZERO, data, priority, maxFee);
        byte[] encoded = TransactionEncoder.signMessage(transaction, signer);
        return new SignedSubmission(signer.getAddress().toLowerCase(Locale.ROOT), nonce.toString(),
                Numeric.toHexString(encoded), Hash.sha3(Numeric.toHexString(encoded)));
    }

    @Override
    public void broadcast(long chainId, String registryAddress, SignedSubmission submission) {
        requireNetwork(chainId, registryAddress);
        // Không cấp nonce/ký lại khi timeout: worker chỉ phát lại đúng bytes đã lưu trước đó.
        requireOwner(registryAddress, submission.signerAddress());
        if (!Hash.sha3(submission.rawTransaction()).equalsIgnoreCase(submission.transactionHash())) {
            throw new BlockchainException("TRANSACTION_MISMATCH", "Giao dịch đã lưu không khớp mã giao dịch.", true);
        }
        var response = rpc(node().ethSendRawTransaction(submission.rawTransaction()));
        if (!submission.transactionHash().equalsIgnoreCase(response.getTransactionHash())) {
            throw new BlockchainException("TRANSACTION_MISMATCH", "RPC trả mã giao dịch không khớp.", true);
        }
    }

    @Override
    public Optional<Receipt> receipt(long chainId, String registryAddress, String onChainDocumentId,
            String documentHash, String recorder, String transactionHash) {
        requireNetwork(chainId, registryAddress);
        Optional<TransactionReceipt> found = rpc(node().ethGetTransactionReceipt(transactionHash)).getTransactionReceipt();
        if (found.isEmpty()) return Optional.empty();
        TransactionReceipt receipt = found.get();
        if (!same(receipt.getTransactionHash(), transactionHash) || !same(receipt.getTo(), registryAddress)
                || !same(receipt.getFrom(), recorder)) throw invalidReceipt();
        EthBlock.Block finalized = rpc(node().ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false)).getBlock();
        if (finalized == null) throw new BlockchainException("FINALITY_UNAVAILABLE", "RPC chưa cung cấp block finalized; hệ thống sẽ đối soát lại.");
        // Kiểm tra canonical sau finalized head để không xác nhận một nhánh vừa bị reorg giữa hai RPC.
        EthBlock.Block canonical = rpc(node().ethGetBlockByNumber(DefaultBlockParameter.valueOf(receipt.getBlockNumber()), false)).getBlock();
        if (canonical == null || !same(canonical.getHash(), receipt.getBlockHash())) {
            // Reorg trước finality: giữ transaction để theo dõi lại, không báo thành công.
            return Optional.empty();
        }
        boolean isFinalized = finalized.getNumber().compareTo(receipt.getBlockNumber()) >= 0;
        if (!receipt.isStatusOK()) {
            if (!isFinalized) throw new BlockchainException("TRANSACTION_REVERT_PENDING_FINALITY", "Giao dịch chưa thành công; chờ finality để đối soát trước khi kết luận.");
            throw new BlockchainException("TRANSACTION_REVERTED", "Giao dịch blockchain không thực thi thành công.", true);
        }
        Log event = receipt.getLogs().stream().filter(log -> matches(log, registryAddress, onChainDocumentId, documentHash, recorder))
                .findFirst().orElseThrow(Web3jRegistryClient::invalidReceipt);
        Instant registeredAt = eventTime(event);
        if (!registeredAt.equals(Instant.ofEpochSecond(canonical.getTimestamp().longValueExact()))) throw invalidReceipt();
        ChainProof stored = readDocument(chainId, registryAddress, onChainDocumentId);
        if (!same(stored.documentHash(), documentHash) || !registeredAt.equals(stored.registeredAt())) throw invalidReceipt();
        return Optional.of(new Receipt(receipt.getBlockNumber().longValueExact(), receipt.getBlockHash(), registeredAt, isFinalized));
    }

    @Override
    public Optional<Registration> findRegistration(long chainId, String registryAddress, String onChainDocumentId, String documentHash) {
        requireNetwork(chainId, registryAddress);
        EthFilter filter = new EthFilter(DefaultBlockParameterName.EARLIEST, DefaultBlockParameterName.LATEST, registryAddress);
        filter.addSingleTopic(EVENT).addSingleTopic(onChainDocumentId);
        // Chỉ dùng khi mapping đã có nhưng DB chưa có tx; RPC giới hạn lịch sử sẽ giữ job chờ đối soát.
        return rpc(node().ethGetLogs(filter)).getLogs().stream().map(item -> (Log) item.get())
                .filter(log -> !log.isRemoved() && log.getTopics() != null && log.getTopics().size() == 4
                        && same(log.getAddress(), registryAddress) && same(log.getTopics().get(0), EVENT)
                        && same(log.getTopics().get(1), onChainDocumentId) && same(log.getTopics().get(2), documentHash))
                .map(log -> new Registration(log.getTransactionHash(), "0x" + log.getTopics().get(3).substring(26)))
                .findFirst();
    }

    private void requireNetwork(long chainId, String registryAddress) {
        if (!BlockchainProperties.supportedChain(chainId) || !BlockchainProperties.validAddress(registryAddress)) {
            throw new BlockchainException("INVALID_NETWORK_CONFIG", "Chỉ hỗ trợ Ethereum Sepolia hoặc mạng local và registry hợp lệ.");
        }
        if (!rpc(node().ethChainId()).getChainId().equals(BigInteger.valueOf(chainId))) {
            throw new BlockchainException("WRONG_CHAIN", "RPC đang trỏ sai mạng của proof.");
        }
        String code = rpc(node().ethGetCode(registryAddress, DefaultBlockParameterName.LATEST)).getCode();
        if (code == null || code.equals("0x") || code.equals("0x0")) {
            throw new BlockchainException("REGISTRY_NOT_FOUND", "Không có smart contract tại địa chỉ registry trên mạng này.");
        }
    }

    private void requireOwner(String registryAddress, String address) {
        List<Type> result = call(registryAddress, new Function("owner", List.of(), List.of(new TypeReference<Address>() {})));
        if (result.size() != 1) throw incompatible();
        if (!same(address, result.getFirst().getValue().toString())) {
            throw new BlockchainException("OWNER_MISMATCH", "Ví hệ thống không phải owner hiện tại của registry.");
        }
    }

    private List<Type> call(String registryAddress, Function function) {
        var request = org.web3j.protocol.core.methods.request.Transaction.createEthCallTransaction(null, registryAddress, FunctionEncoder.encode(function));
        var response = rpc(node().ethCall(request, DefaultBlockParameterName.LATEST));
        if (response.isReverted()) throw incompatible();
        try {
            return FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        } catch (RuntimeException invalid) {
            throw incompatible();
        }
    }

    private synchronized Web3j node() {
        if (web3 != null) return web3;
        if (!properties.isRpcConfigured()) throw new BlockchainException("RPC_NOT_CONFIGURED", "Chưa cấu hình RPC blockchain hợp lệ.");
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(properties.getRpcTimeoutSeconds(), TimeUnit.SECONDS)
                .readTimeout(properties.getRpcTimeoutSeconds(), TimeUnit.SECONDS)
                .callTimeout(properties.getRpcTimeoutSeconds(), TimeUnit.SECONDS).build();
        web3 = Web3j.build(new HttpService(properties.getRpcUrl(), http, false));
        return web3;
    }

    private synchronized Credentials signer() {
        if (credentials != null) return credentials;
        String privateKey = properties.getPrivateKey();
        if (privateKey == null || privateKey.isBlank()) {
            throw new BlockchainException("PRIVATE_KEY_NOT_CONFIGURED", "Chưa cấu hình private key của ví hệ thống.");
        }
        privateKey = privateKey.strip();
        if (!privateKey.matches("(?:0[xX])?[0-9a-fA-F]{64}")) throw invalidPrivateKey();
        if (privateKey.length() == 66) privateKey = privateKey.substring(2);
        try {
            BigInteger scalar = new BigInteger(privateKey, 16);
            if (scalar.signum() <= 0 || scalar.compareTo(Sign.CURVE_PARAMS.getN()) >= 0) throw invalidPrivateKey();
            credentials = Credentials.create(privateKey);
            return credentials;
        } catch (RuntimeException invalid) {
            // Không giữ cause/message từ SDK vì có thể chứa khóa đầu vào.
            throw invalidPrivateKey();
        }
    }

    private static BlockchainException invalidPrivateKey() {
        return new BlockchainException("PRIVATE_KEY_INVALID", "Private key phải là khóa secp256k1 hợp lệ gồm 64 ký tự hex, có thể thêm 0x.");
    }

    private static <T extends Response<?>> T rpc(Request<?, T> request) {
        try {
            T response = request.send();
            if (response.hasError()) throw new BlockchainException("RPC_REJECTED", "RPC từ chối yêu cầu; hệ thống sẽ đối soát trước khi thử lại.");
            return response;
        } catch (BlockchainException safe) {
            throw safe;
        } catch (Exception unavailable) {
            throw new BlockchainException("RPC_UNAVAILABLE", "Chưa đọc/gửi được blockchain; kết quả giao dịch cần được đối soát.");
        }
    }

    private static Bytes32 bytes32(String value) {
        if (value == null || !value.matches("0x[0-9a-fA-F]{64}") || value.equals("0x" + "0".repeat(64))) {
            throw new BlockchainException("INVALID_BYTES32", "ID và hash blockchain phải là bytes32 khác zero.", true);
        }
        return new Bytes32(Numeric.hexStringToByteArray(value));
    }

    static boolean matches(Log log, String registry, String id, String hash, String recorder) {
        return !log.isRemoved() && same(log.getAddress(), registry) && log.getTopics() != null && log.getTopics().size() == 4
                && same(log.getTopics().get(0), EVENT) && same(log.getTopics().get(1), id)
                && same(log.getTopics().get(2), hash) && same(log.getTopics().get(3), "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(recorder));
    }

    private static Instant eventTime(Log event) {
        try {
            if (event.getData() == null || !event.getData().matches("0x[0-9a-fA-F]{64}")) throw invalidReceipt();
            return Instant.ofEpochSecond(Numeric.toBigInt(event.getData()).longValueExact());
        } catch (ArithmeticException invalid) {
            throw invalidReceipt();
        }
    }

    private static boolean same(String first, String second) { return first != null && second != null && first.equalsIgnoreCase(second); }
    private static BlockchainException invalidReceipt() { return new BlockchainException("INVALID_RECEIPT", "Receipt/event không khớp proof đã gửi; cần đối soát.", true); }
    private static BlockchainException incompatible() { return new BlockchainException("INCOMPATIBLE_REGISTRY", "Registry không trả dữ liệu đúng ABI đã cấu hình."); }

    @PreDestroy
    void close() { if (web3 != null) web3.shutdown(); }
}
