package com.hs.payment.service.qr;

import java.math.BigDecimal;

public interface VietQrProvider {

    String generateQrImageUrl(
            String bankBinOrCode,
            String accountNumber,
            String accountHolderName,
            BigDecimal amount,
            String transferReference
    );
}
