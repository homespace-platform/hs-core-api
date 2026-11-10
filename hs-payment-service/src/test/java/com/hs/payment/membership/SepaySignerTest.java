package com.hs.payment.membership;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SepaySignerTest {
    @Test
    void signsDocumentedFieldOrderAsBase64HmacSha256() {
        var fields = Map.of("order_invoice_number", "INV", "operation", "PURCHASE",
                "currency", "VND", "merchant", "M", "order_amount", "100000");
        assertEquals("mnDPcBYdRthGiqUlt+vN2DA1NvU0Rgy7MV+HHikeo7Q=",
                new SepaySigner("test").sign(fields));
    }
}
