package com.hs.contract.pdf;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureInterface;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.SignatureOptions;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSignerInfoVerifierBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.operator.DefaultSignatureAlgorithmIdentifierFinder;
import org.bouncycastle.util.Store;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * Engine ký PDF hai bước:
 *
 * <ol>
 *   <li>{@link #prepareForSigning(byte[], SigningParams)} — thêm placeholder vào PDF và
 *       tính hash (digest) của vùng cần ký. Kết quả trả về bao gồm prepared PDF bytes,
 *       vị trí placeholder, và hex hash để gửi lên VNPT.</li>
 *   <li>{@link #embedSignature(PrepareResult, String, String)} — nhúng signature_value
 *       từ VNPT vào đúng vị trí placeholder trong prepared PDF.</li>
 * </ol>
 *
 * <p><b>Quan trọng:</b> Không được gọi {@code prepareForSigning} lần thứ hai cho cùng
 * một giao dịch sau khi VNPT đã trả về signature_value. Mọi thay đổi dù nhỏ sẽ làm
 * byte array thay đổi và hash sẽ không khớp.</p>
 *
 * <p>Hỗ trợ ký incremental (chỉ append), không load lại toàn bộ PDF → an toàn khi
 * ký nhiều lần (chữ ký chủ nhà không bị hỏng khi người thuê ký).</p>
 */
@Slf4j
@Component
public class PdfSignatureEngine {

    /**
     * Kích thước tối đa dành cho signature container (CMS/PKCS#7).
     * 64KB là đủ cho hầu hết cert chain. Nếu cần hơn, tăng lên.
     */
    private static final int SIGNATURE_CONTAINER_SIZE = 65536;

    // =========================================================================
    // Data classes
    // =========================================================================

    public record SigningParams(
            String reason,
            String location,
            String contactInfo,
            String signerName,
            String signerRole
    ) {}

    /** Add a fixed final page before the first signature so both parties sign the same revision. */
    public byte[] addSignaturePage(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 15);
                stream.beginText();
                stream.newLineAtOffset(68, 748);
                stream.showText("DIGITAL SIGNATURES - VNPT SMARTCA");
                stream.endText();
                drawSignatureBox(stream, 55, "PARTY A - LANDLORD");
                drawSignatureBox(stream, 307, "PARTY B - TENANT");
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private void drawSignatureBox(PDPageContentStream stream, int x, String label) throws IOException {
        stream.addRect(x, 545, 228, 135);
        stream.stroke();
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 10);
        stream.beginText();
        stream.newLineAtOffset(x + 12, 651);
        stream.showText(label);
        stream.endText();
    }

    /**
     * Kết quả sau khi prepare PDF.
     * Giữ toàn bộ thông tin cần thiết để embed signature sau này.
     */
    public record PrepareResult(
            byte[] preparedPdfBytes,
            long placeholderOffset,
            int placeholderLength,
            long[] byteRange,          // [p0, l0, p1, l1]
            String hashHex,            // hex của SHA-256 của vùng ByteRange
            java.time.Instant signDate
    ) {
        /** Trả về byteRange dưới dạng "p0,l0,p1,l1" để lưu DB. */
        public String byteRangeAsString() {
            return byteRange[0] + "," + byteRange[1] + "," + byteRange[2] + "," + byteRange[3];
        }

        public static long[] parseByteRange(String s) {
            String[] parts = s.split(",");
            return new long[]{Long.parseLong(parts[0].trim()), Long.parseLong(parts[1].trim()),
                    Long.parseLong(parts[2].trim()), Long.parseLong(parts[3].trim())};
        }
    }

    // =========================================================================
    // Step 1: Prepare PDF for signing
    // =========================================================================

    /**
     * Thêm signature placeholder vào PDF và tính hash.
     *
     * <p>Dùng incremental update (append) nên không làm mất hiệu lực chữ ký cũ.</p>
     *
     * @param pdfBytes  bytes của PDF gốc (hoặc đã ký một phần)
     * @param params    thông tin signer để điền vào trường chữ ký
     * @return {@link PrepareResult} chứa preparedPdfBytes và hashHex để gửi VNPT
     */
    public PrepareResult prepareForSigning(byte[] pdfBytes, SigningParams params) throws Exception {
        log.debug("PdfSignatureEngine.prepareForSigning: inputSize={}", pdfBytes.length);

        // Holder để capture hash và byteRange từ bên trong SignatureInterface
        long[] capturedByteRange = new long[4];
        byte[][] capturedContent = new byte[1][];
        String[] capturedHashHex = new String[1];

        SignatureInterface sigInterface = content -> {
            // content là InputStream đọc vùng ByteRange (không bao gồm placeholder)
            // Tính hash SHA-256 của vùng này
            java.security.MessageDigest md;
            try {
                md = java.security.MessageDigest.getInstance("SHA-256");
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 unavailable", e);
            }
            byte[] buf = new byte[8192];
            int n;
            while ((n = content.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            byte[] hash = md.digest();
            capturedHashHex[0] = toHex(hash);
            log.debug("PDF digest (SHA-256): {}...", capturedHashHex[0].substring(0, 16));

            // Trả về placeholder bytes (sẽ bị ghi đè lúc embed)
            // Kích thước phải khớp với SIGNATURE_CONTAINER_SIZE
            return new byte[SIGNATURE_CONTAINER_SIZE / 2]; // hex length / 2 = bytes
        };

        java.time.Instant signDate = java.time.Instant.now();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(signDate.toEpochMilli());

        PDSignature signature = new PDSignature();
        signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
        signature.setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
        signature.setSignDate(cal);
        if (params.signerName() != null) signature.setName(params.signerName());
        if (params.reason() != null) signature.setReason(params.reason());
        if (params.location() != null) signature.setLocation(params.location());
        if (params.contactInfo() != null) signature.setContactInfo(params.contactInfo());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            if (doc.getNumberOfPages() == 0) throw new IOException("PDF has no pages");
            PDPage last = doc.getPage(doc.getNumberOfPages() - 1);
            boolean landlord = "LANDLORD".equals(params.signerRole());
            try (PDPageContentStream stream = new PDPageContentStream(doc, last,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 9);
                stream.beginText();
                stream.newLineAtOffset(landlord ? 67 : 319, 610);
                stream.showText("SIGNED WITH VNPT SMARTCA");
                stream.newLineAtOffset(0, -15);
                stream.showText(signDate.toString().replaceAll("[^\\x20-\\x7E]", ""));
                stream.endText();
            }
            SignatureOptions options = new SignatureOptions();
            options.setPreferredSignatureSize(SIGNATURE_CONTAINER_SIZE);

            doc.addSignature(signature, sigInterface, options);
            doc.saveIncremental(baos);
        }

        byte[] preparedPdf = baos.toByteArray();

        // Tìm ByteRange và vị trí placeholder trong prepared PDF
        PlaceholderLocation loc = findPlaceholderLocation(preparedPdf);
        if (capturedHashHex[0] == null) {
            throw new IOException("PDFBox did not supply signing bytes");
        }

        log.info("PdfSignatureEngine prepared: size={}, placeholderOffset={}, placeholderLen={}, hash={}...",
                preparedPdf.length, loc.offset(), loc.length(), capturedHashHex[0].substring(0, 16));

        return new PrepareResult(
                preparedPdf,
                loc.offset(),
                loc.length(),
                loc.byteRange(),
                capturedHashHex[0],
                signDate
        );
    }

    // =========================================================================
    // Step 2: Embed signature from VNPT into prepared PDF
    // =========================================================================

    /**
     * Nhúng signature_value (hex hoặc base64) từ VNPT vào prepared PDF.
     *
     * <p>Chỉ ghi đè vùng placeholder; không đọc lại hay tái tạo PDF → an toàn.</p>
     *
     * @param prepared       kết quả từ {@link #prepareForSigning}
     * @param signatureValue signature_value từ VNPT (base64 hoặc hex)
     * @param certDataBase64 cert_data từ VNPT (base64 DER X.509), có thể null
     * @return PDF bytes hoàn chỉnh với chữ ký đã nhúng
     */
    public byte[] embedSignature(PrepareResult prepared, String signatureValue, String certDataBase64) throws Exception {
        log.debug("PdfSignatureEngine.embedSignature: preparedSize={}", prepared.preparedPdfBytes().length);

        if (signatureValue == null || signatureValue.isBlank()) {
            throw new IllegalArgumentException("signatureValue must not be blank");
        }

        // Decode signature_value — VNPT có thể trả base64 hoặc hex
        byte[] cmsSignatureBytes = toCmsSignature(prepared.preparedPdfBytes(), prepared.byteRange(),
                signatureValue, certDataBase64);

        // Encode thành hex để ghi vào placeholder (PDF dùng hex encoding cho Contents)
        String signatureHex = toHex(cmsSignatureBytes);

        if (signatureHex.length() > prepared.placeholderLength()) {
            throw new IllegalArgumentException(
                    "Signature hex too large: " + signatureHex.length() + " > " + prepared.placeholderLength());
        }

        // Pad signature hex với zeros đến đúng kích thước placeholder
        String paddedHex = signatureHex + "0".repeat(prepared.placeholderLength() - signatureHex.length());

        // Ghi vào đúng vị trí trong prepared PDF bytes
        byte[] result = Arrays.copyOf(prepared.preparedPdfBytes(), prepared.preparedPdfBytes().length);
        byte[] hexBytes = paddedHex.toUpperCase().getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(hexBytes, 0, result, (int) prepared.placeholderOffset(), hexBytes.length);

        log.info("PdfSignatureEngine embedded: signatureBytes={}, outputSize={}",
                cmsSignatureBytes.length, result.length);
        return result;
    }

    /**
     * Nhúng vào prepared PDF đã lưu trong storage (biết offset từ DB).
     */
    public byte[] embedSignatureFromStorage(
            byte[] preparedPdfBytes,
            long placeholderOffset,
            int placeholderLength,
            String signatureValue,
            String certDataBase64,
            String byteRangeText
    ) throws Exception {
        if (signatureValue == null || signatureValue.isBlank()) {
            throw new IllegalArgumentException("signatureValue must not be blank");
        }
        byte[] cmsBytes = toCmsSignature(preparedPdfBytes, PrepareResult.parseByteRange(byteRangeText),
                signatureValue, certDataBase64);
        String hex = toHex(cmsBytes);
        if (hex.length() > placeholderLength) {
            throw new IllegalArgumentException("Signature hex too large: " + hex.length() + " > " + placeholderLength);
        }
        String paddedHex = hex + "0".repeat(placeholderLength - hex.length());
        byte[] result = Arrays.copyOf(preparedPdfBytes, preparedPdfBytes.length);
        System.arraycopy(paddedHex.toUpperCase().getBytes(StandardCharsets.US_ASCII),
                0, result, (int) placeholderOffset, paddedHex.length());
        log.info("embedSignatureFromStorage: offset={}, sigBytes={}, outputSize={}",
                placeholderOffset, cmsBytes.length, result.length);
        return result;
    }

    // =========================================================================
    // Step 3: Verify signatures in final PDF
    // =========================================================================

    /**
     * Xác minh tất cả chữ ký số trong PDF.
     *
     * @return danh sách kết quả xác minh (một entry per signature)
     */
    public List<VerificationResult> verifySignatures(byte[] signedPdfBytes) {
        List<VerificationResult> results = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(signedPdfBytes)) {
            for (PDSignature pdfSignature : doc.getSignatureDictionaries()) {
                int[] range = pdfSignature.getByteRange();
                if (range == null || range.length != 4 || range[0] != 0
                        || (long) range[0] + range[1] > range[2]
                        || (long) range[2] + range[3] > signedPdfBytes.length) {
                    results.add(new VerificationResult("unknown", false, "Invalid PDF ByteRange"));
                    continue;
                }

                try {
                    CMSSignedData cms = new CMSSignedData(
                            new CMSProcessableByteArray(pdfSignature.getSignedContent(signedPdfBytes)),
                            pdfSignature.getContents(signedPdfBytes));
                    Store<X509CertificateHolder> certStore = cms.getCertificates();
                    Collection<SignerInformation> signers = cms.getSignerInfos().getSigners();

                    if (signers.size() != 1) {
                        results.add(new VerificationResult("unknown", false, "Expected one CMS signer"));
                    }
                    for (SignerInformation signer : signers) {
                        @SuppressWarnings("unchecked")
                        Collection<X509CertificateHolder> certs = certStore.getMatches(signer.getSID());
                        boolean valid = false;
                        String subject = "unknown";
                        if (!certs.isEmpty()) {
                            X509Certificate cert = new JcaX509CertificateConverter().getCertificate(certs.iterator().next());
                            subject = cert.getSubjectX500Principal().getName();
                            try {
                                valid = signer.verify(new JcaSignerInfoVerifierBuilder(
                                        new JcaDigestCalculatorProviderBuilder().build())
                                        .build(cert));
                            } catch (Exception e) {
                                log.warn("Signature verify failed for {}: {}", subject, e.getMessage());
                            }
                        }
                        results.add(new VerificationResult(subject, valid, null));
                    }
                } catch (Exception e) {
                    results.add(new VerificationResult("unknown", false, e.getMessage()));
                }
            }
        } catch (Exception e) {
            log.error("verifySignatures failed: {}", e.getMessage(), e);
            results.add(new VerificationResult("error", false, e.getMessage()));
        }
        return results;
    }

    public record VerificationResult(String signerSubject, boolean valid, String errorMessage) {}

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private record PlaceholderLocation(long offset, int length, long[] byteRange) {}

    /**
     * Tìm vị trí của signature placeholder hex trong PDF bytes.
     * PDFBox ghi placeholder là chuỗi hex '0000...0000' trong trường /Contents.
     */
    private PlaceholderLocation findPlaceholderLocation(byte[] pdfBytes) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDDocumentCatalog catalog = doc.getDocumentCatalog();
            if (catalog.getAcroForm() == null) {
                throw new IOException("No AcroForm found in prepared PDF");
            }

            PlaceholderLocation latest = null;
            for (var iterator = catalog.getAcroForm().getFieldIterator(); iterator.hasNext();) {
                var field = iterator.next();
                if (!"Sig".equals(field.getFieldType())) continue;
                COSDictionary v = (COSDictionary) field.getCOSObject().getDictionaryObject(COSName.V);
                if (v == null) continue;

                COSArray byteRangeArray = (COSArray) v.getDictionaryObject(COSName.BYTERANGE);
                if (byteRangeArray == null || byteRangeArray.size() != 4) continue;

                long p0 = byteRangeArray.getInt(0);
                long l0 = byteRangeArray.getInt(1);
                long p1 = byteRangeArray.getInt(2);
                // long l1 = byteRangeArray.getInt(3); // not needed

                // placeholder starts right after ByteRange[0..l0]
                long placeholderStart = p0 + l0 + 1; // +1 for '<'
                long placeholderEnd = p1 - 1;          // -1 for '>'
                long placeholderLength = placeholderEnd - placeholderStart;

                PlaceholderLocation candidate = new PlaceholderLocation(
                        placeholderStart,
                        (int) placeholderLength,
                        new long[]{p0, l0, p1, byteRangeArray.getInt(3)}
                );
                if (latest == null || candidate.offset() > latest.offset()) latest = candidate;
            }
            if (latest != null) return latest;
        }
        throw new IOException("No signature field found in prepared PDF — did PDFBox add it correctly?");
    }

    private byte[] decodeSignatureValue(String signatureValue) {
        String s = signatureValue.trim();
        // Hex is also valid Base64 alphabet: check it first to avoid silent corruption.
        if (s.length() % 2 == 0 && s.matches("[0-9a-fA-F]+")) return hexToBytes(s);
        // Thử decode base64 trước
        try {
            byte[] decoded = Base64.getDecoder().decode(s);
            if (decoded.length > 0) {
                log.debug("Decoded signatureValue as Base64: {} bytes", decoded.length);
                return decoded;
            }
        } catch (Exception ignored) {}

        // Thử hex
        try {
            if (s.matches("[0-9a-fA-F]+")) {
                byte[] decoded = hexToBytes(s);
                log.debug("Decoded signatureValue as Hex: {} bytes", decoded.length);
                return decoded;
            }
        } catch (Exception ignored) {}

        // Base64 URL-safe
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(s);
            if (decoded.length > 0) return decoded;
        } catch (Exception ignored) {}

        throw new IllegalArgumentException("Cannot decode signatureValue — not valid Base64 or Hex");
    }

    private boolean isCmsSignature(byte[] bytes) {
        // CMS/PKCS#7 bắt đầu bằng ASN.1 SEQUENCE tag 0x30
        return bytes.length > 0 && bytes[0] == 0x30;
    }

    private byte[] toCmsSignature(byte[] pdf, long[] range, String value, String certBase64) throws Exception {
        byte[] decoded = decodeSignatureValue(value);
        try {
            new CMSSignedData(decoded);
            return decoded;
        } catch (CMSException ignored) {
            // SmartCA Web API returns a raw signature over SHA-256(PDF ByteRange).
        }
        if (certBase64 == null || certBase64.isBlank()) {
            throw new IOException("Certificate is required to build PDF CMS signature");
        }
        byte[] certBytes = Base64.getDecoder().decode(certBase64
                .replaceAll("-----BEGIN CERTIFICATE-----|-----END CERTIFICATE-----|\\s", ""));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certBytes));
        String signatureAlgorithm = "EC".equalsIgnoreCase(cert.getPublicKey().getAlgorithm())
                ? "SHA256withECDSA" : "SHA256withRSA";
        if (range.length != 4 || range[0] != 0 || range[1] < 0 || range[2] < range[1]
                || range[3] < 0 || range[2] + range[3] > pdf.length) {
            throw new IOException("Invalid PDF ByteRange");
        }
        ByteArrayOutputStream signedContent = new ByteArrayOutputStream();
        signedContent.write(pdf, (int) range[0], (int) range[1]);
        signedContent.write(pdf, (int) range[2], (int) range[3]);
        ContentSigner remoteSigner = new ContentSigner() {
            private final ByteArrayOutputStream output = new ByteArrayOutputStream();
            @Override public org.bouncycastle.asn1.x509.AlgorithmIdentifier getAlgorithmIdentifier() {
                return new DefaultSignatureAlgorithmIdentifierFinder().find(signatureAlgorithm);
            }
            @Override public OutputStream getOutputStream() { return output; }
            @Override public byte[] getSignature() { return decoded; }
        };
        CMSSignedDataGenerator generator = new CMSSignedDataGenerator();
        var signerInfo = new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build());
        signerInfo.setDirectSignature(true);
        generator.addSignerInfoGenerator(signerInfo.build(remoteSigner, cert));
        generator.addCertificates(new JcaCertStore(List.of(cert)));
        return generator.generate(new CMSProcessableByteArray(signedContent.toByteArray()), false).getEncoded();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
