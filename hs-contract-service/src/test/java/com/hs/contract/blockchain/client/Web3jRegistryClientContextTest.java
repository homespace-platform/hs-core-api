package com.hs.contract.blockchain.client;

import com.hs.contract.blockchain.api.ContractDocumentProofQueryService;
import com.hs.contract.blockchain.config.BlockchainProperties;
import com.hs.contract.blockchain.persistence.ContractDocumentProofRepository;
import com.hs.contract.service.ContractService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class Web3jRegistryClientContextTest {
    @Test void springCreatesTheRegistryClientAndItsConsumerWithoutRpcOrPrivateKey() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ContractService.class, () -> mock(ContractService.class));
            context.registerBean(ContractDocumentProofRepository.class, () -> mock(ContractDocumentProofRepository.class));
            context.register(BlockchainProperties.class, Web3jRegistryClient.class, ContractDocumentProofQueryService.class);
            // Khởi tạo bằng Spring thật để phát hiện constructor ambiguity mà test dùng new() không bắt được.
            context.refresh();

            var client = context.getBean(Web3jRegistryClient.class);
            assertSame(client, context.getBean(RegistryClient.class));
            assertNotNull(context.getBean(ContractDocumentProofQueryService.class));
            assertNull(ReflectionTestUtils.getField(client, "web3"));
            assertNull(ReflectionTestUtils.getField(client, "credentials"));
        }
    }
}
