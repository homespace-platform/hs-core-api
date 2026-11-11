package com.hs.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.common.advice.entity.AppException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class EthVndPriceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-11T10:00:00Z");

    @Test
    void roundsWeiUpWithoutFloatingPoint() {
        assertEquals(new BigInteger("333333333333333334"),
                EthVndPriceService.weiFor(new BigDecimal("1000000"), new BigDecimal("3000000")));
    }

    @Test
    void acceptsFreshMainnetPrice() throws Exception {
        var body = mapper.readTree("{\"ethereum\":{\"vnd\":75000000,\"last_updated_at\":1794391200}}");
        assertEquals(new BigDecimal("75000000"), EthVndPriceService.parse(body, now).ethVnd());
    }

    @Test
    void rejectsMissingZeroAndStalePrice() throws Exception {
        assertThrows(AppException.class, () -> EthVndPriceService.parse(mapper.readTree("{}"), now));
        assertThrows(AppException.class, () -> EthVndPriceService.parse(mapper.readTree("{\"ethereum\":{\"vnd\":0,\"last_updated_at\":1794391200}}"), now));
        assertThrows(AppException.class, () -> EthVndPriceService.parse(mapper.readTree("{\"ethereum\":{\"vnd\":75000000,\"last_updated_at\":1794390000}}"), now));
    }
}
