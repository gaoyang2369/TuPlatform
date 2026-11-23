package cn.linke;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.web.multipart.MultipartFile;

final class AvatarImage {
    private static final int SIDE = 300;
    private static final long MAX_PIXELS = 24_000_000L;

    static ApiError tooLarge() {
        return new ApiError(413, "AVATAR_TOO_LARGE", "头像文件不能超过 2 MB");
    }

    static byte[] jpeg(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ApiError(422, "AVATAR_REQUIRED", "请选择头像图片");
        if (file.getSize() > 2 * 1024 * 1024) throw tooLarge();
        try (var input = file.getInputStream(); var stream = new MemoryCacheImageInputStream(input)) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                String type = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "webp" -> "image/webp";
                    default -> throw invalid();
                };
                if (!type.equalsIgnoreCase(file.getContentType())) throw invalid();
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS)
                    throw new ApiError(422, "AVATAR_DIMENSIONS", "图片尺寸过大或无效，请先缩小图片");
                int side = Math.min(width, height);
                var param = reader.getDefaultReadParam();
                param.setSourceRegion(new Rectangle((width - side) / 2, (height - side) / 2, side, side));
                int sample = Math.max(1, side / (SIDE * 2));
                param.setSourceSubsampling(sample, sample, 0, 0);
                var source = reader.read(0, param);
                var result = new BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_RGB);
                var g = result.createGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(source, 0, 0, SIDE, SIDE, Color.WHITE, null);
                } finally { g.dispose(); }
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(result, "JPEG", bytes);
                return bytes.toByteArray();
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException e) {
            throw invalid();
        }
    }

    private static ApiError invalid() {
        return new ApiError(422, "INVALID_AVATAR", "请选择可正常打开的 JPG、PNG 或 WebP 图片");
    }
}
