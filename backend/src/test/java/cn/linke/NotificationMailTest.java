package cn.linke;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class NotificationMailTest {
    private final JavaMailSender smtp = mock(JavaMailSender.class);
    private final ObjectMapper json = new ObjectMapper();
    private final String to = "recipient@example.invalid";
    private final UUID id = UUID.randomUUID();
    private final String url = "http://127.0.0.1:8180/?view=matches&match=123";

    @Test void missingSmtpPrintsStructuredPreview(CapturedOutput output) throws Exception {
        var mail = new NotificationMail(smtp,json,"test@example.invalid",true,"",true,"","");
        assertEquals("logged",mail.deliver(id,to,"测试主题","第一行\n第二行",url));
        String line = output.getOut().lines().filter(s -> s.contains("本地通知邮件 {")).findFirst().orElseThrow();
        var preview = json.readTree(line.substring(line.indexOf("本地通知邮件 ") + "本地通知邮件 ".length()));
        assertEquals(to,preview.get("recipient").asText());
        assertEquals("邻课 · 测试主题",preview.get("subject").asText());
        assertEquals("第一行\n第二行",preview.get("content").asText());
        assertEquals(url,preview.get("target_url").asText());
        verifyNoInteractions(smtp);
    }

    @Test void smtpBuildsEscapedHtmlAndPlainText() throws Exception {
        var message = new MimeMessage(Session.getInstance(new Properties()));
        when(smtp.createMimeMessage()).thenReturn(message);
        var mail = new NotificationMail(smtp,json,"test@example.invalid",true,"mail.example.invalid",true,"test","test");
        assertEquals("sent",mail.deliver(id,to,"审核结果","请核对 <测试> & 补充",url));
        verify(smtp).send(message);
        assertEquals("<" + id + "@notifications.linke.invalid>",message.getMessageID());
        assertEquals(to,message.getAllRecipients()[0].toString());
        assertEquals("邻课 · 审核结果",message.getSubject());
        message.saveChanges();
        String html = body(message,"text/html");
        assertTrue(html.contains("请核对 &lt;测试&gt; &amp; 补充"));
        assertTrue(html.contains("href=\"" + url.replace("&","&amp;") + "\""));
        assertTrue(html.contains("前往邻课查看"));
        assertTrue(body(message,"text/plain").contains(url));
    }

    private static String body(Part part, String type) throws Exception {
        if (part.isMimeType(type)) return part.getContent().toString();
        if (part.getContent() instanceof Multipart parts) {
            for (int i=0; i<parts.getCount(); i++) {
                String found = body(parts.getBodyPart(i),type);
                if (!found.isEmpty()) return found;
            }
        }
        return "";
    }
}
