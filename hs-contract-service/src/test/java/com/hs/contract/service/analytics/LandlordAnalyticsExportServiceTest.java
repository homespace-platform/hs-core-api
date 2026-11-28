package com.hs.contract.service.analytics;

import com.hs.common.advice.entity.AppException;
import com.hs.contract.dto.analytics.*;
import com.hs.contract.dto.analytics.LandlordAnalyticsResponse.*;
import com.hs.payment.receipt.PaymentReceiptPdfRenderer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LandlordAnalyticsExportServiceTest {
    @Test void csvHasBomEscapesQuotesAndNeutralizesFormulasIncludingWhitespace() {
        var row=new ArrayList<Object>(Arrays.asList("=cmd", "  @SUM(1)", "a,\"b\"", "\t+cmd", new BigDecimal("-12.50"), Instant.parse("2026-10-31T17:00:00Z"), null));
        String csv=new String(LandlordAnalyticsExportService.csv(List.of("Tên"),List.of(row)),StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFF\"Tên\"\r\n"));
        assertTrue(csv.contains("\"'=cmd\""));assertTrue(csv.contains("\"'  @SUM(1)\""));
        assertTrue(csv.contains("\"a,\"\"b\"\"\""));assertTrue(csv.contains("\"'\t+cmd\""));
        assertTrue(csv.contains("\"-12.50\""));assertTrue(csv.contains("01/11/2026 00:00"));
    }
    @Test void rowLimitRejectsOnlyAboveTwentyThousand() {
        LandlordAnalyticsExportService.checkSize(20_000);
        assertEquals(422,assertThrows(AppException.class,()->LandlordAnalyticsExportService.checkSize(20_001)).getStatusCode().value());
    }
    @Test void invalidReportFormatOrGranularityIsRejectedBeforeReadingData() {
        var analytics=mock(LandlordAnalyticsService.class);
        var service=new LandlordAnalyticsExportService(analytics,mock(LandlordAnalyticsQueries.class),mock(PaymentReceiptPdfRenderer.class));
        var q=new LandlordAnalyticsQuery(null,null,null,null,Set.of(),0,0,false);
        assertThrows(AppException.class,()->service.export("owner",q,"unknown","csv","receipt"));
        assertThrows(AppException.class,()->service.export("owner",q,"funnel","xlsx","receipt"));
        assertThrows(AppException.class,()->service.export("owner",q,"funnel","csv","month"));
        verifyNoInteractions(analytics);
    }
    @Test void disabledPdfReturns503AndNeverCallsAnyExternalService() {
        var analytics=mock(LandlordAnalyticsService.class);
        when(analytics.analytics(eq("owner"),any())).thenReturn(new LandlordAnalyticsResponse(Instant.EPOCH,"2026-11","2026-11",null,null,null,null,null,new Funnel(new Views(0,true,null),0,0,0)));
        var service=new LandlordAnalyticsExportService(analytics,mock(LandlordAnalyticsQueries.class),new PaymentReceiptPdfRenderer("http://127.0.0.1:1",false));
        var error=assertThrows(AppException.class,()->service.export("owner",new LandlordAnalyticsQuery(null,null,null,null,Set.of(),0,0,false),"funnel","pdf","receipt"));
        assertEquals(503,error.getStatusCode().value());
    }
}
