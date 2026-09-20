package com.hs.payment.service;

import com.hs.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
@RequiredArgsConstructor
public class TransferReferenceGenerator {

    private final PaymentRequestRepository paymentRequestRepository;
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generateUniqueReference() {
        for (int i = 0; i < 20; i++) {
            StringBuilder sb = new StringBuilder("HS");
            for (int j = 0; j < 8; j++) {
                int index = RANDOM.nextInt(ALPHABET.length());
                sb.append(ALPHABET.charAt(index));
            }
            String candidate = sb.toString();
            if (!paymentRequestRepository.existsByTransferReference(candidate)) {
                return candidate;
            }
        }
        // Fallback with timestamp
        long suffix = System.currentTimeMillis() % 100000000L;
        return "HS" + String.format("%08d", suffix);
    }
}
