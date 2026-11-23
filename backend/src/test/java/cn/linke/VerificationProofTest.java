package cn.linke;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class VerificationProofTest {
    private MockMultipartFile file(String type, byte[] bytes) {
        return new MockMultipartFile("file", "original-name", type, bytes);
    }
    @Test void acceptsReadableImagesAndPreservesOriginalForReview() throws Exception {
        for (String format : new String[]{"jpeg", "png"}) {
            var bytes = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(20, 12, BufferedImage.TYPE_INT_RGB), format, bytes);
            var proof = VerificationProof.from(file("image/" + format, bytes.toByteArray()));
            assertArrayEquals(bytes.toByteArray(), proof.bytes());
            assertThrows(ApiError.class, () -> VerificationProof.from(file("image/webp", bytes.toByteArray())));
        }
        var webp = Base64.getDecoder().decode("UklGRjoAAABXRUJQVlA4IC4AAADQAQCdASoGAAQAAUAmJaACdLoB+AADsAD+82mX/mwIGaH0wf+mkeNI8aR8poAA");
        assertEquals("webp", VerificationProof.from(file("image/webp", webp)).extension());
    }
    @Test void acceptsReadablePdfAndRejectsInvalidOrOversizeContent() throws Exception {
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new ByteArrayOutputStream()) {
            pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            pdf.save(out);
            assertEquals("pdf", VerificationProof.from(file("application/pdf", out.toByteArray())).extension());
        }
        assertEquals(422, assertThrows(ApiError.class, () -> VerificationProof.from(file("application/pdf", "%PDF-test".getBytes()))).status);
        assertEquals(422, assertThrows(ApiError.class, () -> VerificationProof.from(file("image/png", "<html>".getBytes()))).status);
        assertEquals(413, assertThrows(ApiError.class, () -> VerificationProof.from(file("image/png", new byte[5242881]))).status);
    }
}
