package com.hs.contract.service.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VietnameseCurrencyTextConverterTest {

    @Test
    void testToWords_tenMillion() {
        String result = VietnameseCurrencyTextConverter.toWords(new BigDecimal("10000000"));
        assertEquals("Mười triệu đồng chẵn", result);
    }

    @Test
    void testToWords_complexAmount() {
        String result = VietnameseCurrencyTextConverter.toWords(new BigDecimal("4850000"));
        assertEquals("Bốn triệu tám trăm năm mươi nghìn đồng chẵn", result);
    }

    @Test
    void testToWords_zero() {
        String result = VietnameseCurrencyTextConverter.toWords(BigDecimal.ZERO);
        assertEquals("Không đồng", result);
    }
}
