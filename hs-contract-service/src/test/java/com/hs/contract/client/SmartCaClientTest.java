package com.hs.contract.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hs.contract.config.SmartCaProperties;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmartCaClientTest {

    private static final String CCCD = "012345678901";
    private final SmartCaClient client = new SmartCaClient(new SmartCaProperties(), new ObjectMapper());

    @Test
    void acceptsValidCodeEvenWhenDisplayStatusIsLocalized() {
        String response = response("VALID", "Đang hoạt động");

        var certificates = client.parseCertificates(response, CCCD);

        assertEquals(1, certificates.size());
        assertEquals("SERIAL-1", certificates.getFirst().serialNumber());
    }

    @Test
    void rejectsExpiredCodeEvenWhenDisplayStatusLooksActive() {
        String response = response("EXPIRED", "Đang hoạt động");

        assertEquals(0, client.parseCertificates(response, CCCD).size());
    }

    @Test
    void rejectsCertificateWithoutMachineReadableStatus() {
        String response = response("", "");

        assertEquals(0, client.parseCertificates(response, CCCD).size());
    }

    @Test
    void acceptsCertificateDataWrappedWithCrLf() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var name = new X500Name("CN=Test,UID=" + CCCD);
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)),
                name, keys.getPublic());
        var cert = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));
        String wrappedCert = Base64.getMimeEncoder(64, new byte[]{'\r', '\n'})
                .encodeToString(cert.getEncoded());
        String response = new ObjectMapper().writeValueAsString(Map.of(
                "status_code", 200,
                "data", Map.of("user_certificates", List.of(Map.of(
                        "serial_number", "SERIAL-1",
                        "cert_status_code", "VALID",
                        "cert_status", "Đang hoạt động",
                        "cert_data", wrappedCert)))));

        assertEquals(1, client.parseCertificates(response, CCCD).size());
    }

    @Test
    void liveProviderDiagnosticWhenExplicitlyConfigured() {
        String cccd = System.getenv("SMARTCA_TEST_CCCD");
        String spId = System.getenv("SMARTCA_SP_ID");
        String spPassword = System.getenv("SMARTCA_SP_PASSWORD");
        String baseUrl = System.getenv("SMARTCA_BASE_URL");
        Assumptions.assumeTrue(cccd != null && spId != null && spPassword != null && baseUrl != null,
                "Live SmartCA check requires explicit credentials and test CCCD");

        SmartCaProperties properties = new SmartCaProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(baseUrl);
        properties.setSpId(spId);
        properties.setSpPassword(spPassword);
        var certificates = new SmartCaClient(properties, new ObjectMapper()).getCertificates(cccd, null);
        System.out.println("Live SmartCA accepted certificate count=" + certificates.size());
        org.junit.jupiter.api.Assertions.assertFalse(certificates.isEmpty());
    }

    private String response(String statusCode, String displayStatus) {
        return """
                {"status_code":200,"data":{"user_certificates":[{
                  "serial_number":"SERIAL-1",
                  "cert_subject":"CN=Test,UID=%s",
                  "cert_status_code":"%s",
                  "cert_status":"%s",
                  "cert_valid_from":"2020-01-01 00:00:00",
                  "cert_valid_to":"2099-01-01 00:00:00"
                }]}}
                """.formatted(CCCD, statusCode, displayStatus);
    }
}
