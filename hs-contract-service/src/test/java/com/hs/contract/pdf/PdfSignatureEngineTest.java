package com.hs.contract.pdf;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class PdfSignatureEngineTest {
    @Test
    void signsTheSamePdfIncrementallyAndRejectsTampering() throws Exception {
        PdfSignatureEngine engine = new PdfSignatureEngine();
        byte[] source;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            source = engine.addSignaturePage(out.toByteArray());
        }

        byte[] landlord = sign(engine, source, "LANDLORD");
        assertEquals(1, engine.verifySignatures(landlord).size());
        assertTrue(engine.verifySignatures(landlord).getFirst().valid());

        byte[] finalPdf = sign(engine, landlord, "TENANT");
        assertEquals(2, engine.verifySignatures(finalPdf).size());
        assertTrue(engine.verifySignatures(finalPdf).stream().allMatch(PdfSignatureEngine.VerificationResult::valid));

        byte[] tampered = finalPdf.clone();
        tampered[100] ^= 1;
        assertFalse(engine.verifySignatures(tampered).stream().allMatch(PdfSignatureEngine.VerificationResult::valid));
    }

    private byte[] sign(PdfSignatureEngine engine, byte[] pdf, String role) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        X500Name name = new X500Name("CN=Test " + role + ",UID=012345678901");
        var builder = new JcaX509v3CertificateBuilder(name, BigInteger.valueOf(System.nanoTime()),
                Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)),
                name, keys.getPublic());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate())));

        var prepared = engine.prepareForSigning(pdf,
                new PdfSignatureEngine.SigningParams("Lease", "Viet Nam", null, role, role));
        long[] range = prepared.byteRange();
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keys.getPrivate());
        signer.update(prepared.preparedPdfBytes(), (int) range[0], (int) range[1]);
        signer.update(prepared.preparedPdfBytes(), (int) range[2], (int) range[3]);
        return engine.embedSignature(prepared, Base64.getEncoder().encodeToString(signer.sign()),
                Base64.getEncoder().encodeToString(certificate.getEncoded()));
    }
}
