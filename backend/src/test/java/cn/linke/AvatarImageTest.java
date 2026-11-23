package cn.linke;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;

class AvatarImageTest {
    private MockMultipartFile file(String type, byte[] bytes) {
        return new MockMultipartFile("file", "ignored-name", type, bytes);
    }
    private byte[] landscape(String format) throws Exception {
        var image = new BufferedImage(600, 200, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Color.BLUE); g.fillRect(200, 0, 200, 200); g.dispose();
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    @Test void cropsMiddleAndEncodesStandardJpeg() throws Exception {
        for (String format : new String[]{"jpeg", "png"}) {
            var bytes = AvatarImage.jpeg(file("image/" + format, landscape(format)));
            assertEquals(0xff, bytes[0] & 255);
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertEquals(300, image.getWidth()); assertEquals(300, image.getHeight());
            assertTrue(new Color(image.getRGB(5, 5)).getBlue() > 240);
        }
    }
    @Test void decodesGenuineWebP() throws Exception {
        byte[] webp = Base64.getDecoder().decode("UklGRjoAAABXRUJQVlA4IC4AAADQAQCdASoGAAQAAUAmJaACdLoB+AADsAD+82mX/mwIGaH0wf+mkeNI8aR8poAA");
        var image = ImageIO.read(new ByteArrayInputStream(AvatarImage.jpeg(file("image/webp", webp))));
        assertEquals(300, image.getWidth());
        assertTrue(new Color(image.getRGB(150, 150)).getGreen() > 100);
    }
    @Test void rejectsUnsupportedMismatchedOversizeAndUnreadableFiles() throws Exception {
        assertEquals(422, assertThrows(ApiError.class, () -> AvatarImage.jpeg(file("image/png", landscape("gif")))).status);
        assertEquals(422, assertThrows(ApiError.class, () -> AvatarImage.jpeg(file("image/webp", landscape("png")))).status);
        assertEquals(422, assertThrows(ApiError.class, () -> AvatarImage.jpeg(file("image/jpeg", new byte[]{-1, -40, -1}))).status);
        assertEquals(413, assertThrows(ApiError.class, () -> AvatarImage.jpeg(file("image/png", new byte[2 * 1024 * 1024 + 1]))).status);
    }
}
