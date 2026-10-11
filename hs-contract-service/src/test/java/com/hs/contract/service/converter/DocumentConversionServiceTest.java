package com.hs.contract.service.converter;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class DocumentConversionServiceTest {
    @Test void returnsPdfFromConverter() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        var service = configured(rest);
        server.expect(requestTo("http://converter/forms/libreoffice/convert")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("%PDF-test", MediaType.APPLICATION_PDF));
        assertArrayEquals("%PDF-test".getBytes(), service.convertDocxToPdf(new byte[]{1}, "test.docx").orElseThrow());
        server.verify();
    }
    @Test void converterTimeoutReturnsNoPdfAndDoesNotRetry() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("http://converter/forms/libreoffice/convert"))
                .andRespond(withException(new SocketTimeoutException("test timeout")));
        assertTrue(configured(rest).convertDocxToPdf(new byte[]{1}, "test.docx").isEmpty());
        server.verify();
    }
    @Test void disabledConversionNeverContactsConverter() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        assertTrue(new DocumentConversionService(rest).convertDocxToPdf(new byte[]{1}, "test.docx").isEmpty());
        server.verify();
    }
    private DocumentConversionService configured(RestTemplate rest) {
        var service = new DocumentConversionService(rest);
        ReflectionTestUtils.setField(service, "gotenbergEnabled", true);
        ReflectionTestUtils.setField(service, "gotenbergUrl", "http://converter/");
        return service;
    }
}
