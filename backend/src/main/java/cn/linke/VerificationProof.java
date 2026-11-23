package cn.linke;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.springframework.web.multipart.MultipartFile;

record VerificationProof(String extension, byte[] bytes) {
    static ApiError tooLarge() { return new ApiError(413, "PROOF_TOO_LARGE", "认证材料不能超过 5 MB"); }
    private static ApiError invalid() {
        return ApiError.invalid("file", "请选择可正常打开的 JPG、PNG、WebP 图片或未加密 PDF");
    }
    static VerificationProof from(MultipartFile file) {
        if (file == null || file.isEmpty()) throw invalid();
        if (file.getSize() > 5 * 1024 * 1024) throw tooLarge();
        try {
            byte[] bytes = file.getBytes();
            if ("application/pdf".equalsIgnoreCase(file.getContentType())) {
                if (bytes.length < 5 || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                    throw invalid();
                try (var input = new RandomAccessReadBuffer(bytes); var pdf = new PDFParser(input).parse(false)) {
                    if (pdf.isEncrypted() || pdf.getNumberOfPages() < 1) throw invalid();
                }
                return new VerificationProof("pdf", bytes);
            }
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!("image/" + format).equalsIgnoreCase(file.getContentType())
                            || !java.util.Set.of("jpeg", "png", "webp").contains(format)) throw invalid();
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || (long)width * height > 48_000_000L)
                        throw ApiError.invalid("file", "图片尺寸过大或无效，请缩小后重试");
                    var param = reader.getDefaultReadParam();
                    int sample = Math.max(1, Math.max(width, height) / 1600);
                    param.setSourceSubsampling(sample, sample, 0, 0);
                    reader.read(0, param);
                    return new VerificationProof(format.equals("jpeg") ? "jpg" : format, bytes);
                } finally { reader.dispose(); }
            }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw invalid();
        }
    }
}
