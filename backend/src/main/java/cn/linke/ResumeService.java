package cn.linke;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdfparser.PDFParser;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
class ResumeService {
    record Attachment(String resumePdfUrl, String resumePdfName, long version) {}
    record Document(String name, byte[] content) {}
    private final JdbcTemplate db;
    private final AuthService auth;

    ResumeService(JdbcTemplate db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }
    static ApiError tooLarge() { return new ApiError(413, "RESUME_TOO_LARGE", "PDF 简历不能超过 5 MB"); }

    private Account owner(String session, Long expectedVersion) {
        Account owner = auth.business(session, "TUTOR");
        if (expectedVersion != null && owner.profileVersion() != expectedVersion)
            throw new ApiError(409, "VERSION_CONFLICT", "资料已在其他页面修改，请读取最新内容");
        return owner;
    }
    Attachment upload(String session, Long expectedVersion, MultipartFile file) {
        Account owner = owner(session, expectedVersion);
        if (file == null || file.isEmpty()) throw ApiError.invalid("file", "请选择 PDF 简历");
        if (file.getSize() > 5 * 1024 * 1024) throw tooLarge();
        String name = Rules.text("file", file.getOriginalFilename(), 180, true);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf") || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(Character::isISOControl))
            throw ApiError.invalid("file", "请选择扩展名为 .pdf 的文件");
        byte[] content;
        try {
            content = file.getBytes();
            if (content.length < 5 || !new String(content, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
                throw ApiError.invalid("file", "文件内容不是 PDF");
            try (var input = new RandomAccessReadBuffer(content); var pdf = new PDFParser(input).parse(false)) {
                pdf.getDocumentCatalog().getPages();
            } catch (InvalidPasswordException encryptedPdf) {
                // The parser recognized PDF encryption. The viewer can supply its password.
            }
        } catch (IOException e) { throw ApiError.invalid("file", "无法读取 PDF，请检查文件是否完整"); }

        UUID id = UUID.randomUUID();
        String url = "/api/v1/resumes/" + id + ".pdf";
        db.update("""
            INSERT INTO teacher_resume_files(account_id,file_id,content) VALUES (?,?,?)
            ON CONFLICT(account_id) DO UPDATE SET file_id=excluded.file_id,content=excluded.content
            """, owner.id(), id, content);
        return update(owner, url, name);
    }
    Attachment delete(String session, Long expectedVersion) {
        Account owner = owner(session, expectedVersion);
        if (db.update("DELETE FROM teacher_resume_files WHERE account_id=?", owner.id()) == 0)
            return new Attachment(null, null, owner.profileVersion());
        return update(owner, null, null);
    }
    private Attachment update(Account owner, String url, String name) {
        db.update("UPDATE profiles SET resume_pdf_url=?,resume_pdf_name=?,version=version+1,updated_at=now() WHERE account_id=?",
            url, name, owner.id());
        return new Attachment(url, name, owner.profileVersion() + 1);
    }
    Document read(String session, UUID fileId) {
        Account viewer = auth.business(session, null);
        var files = db.query("""
            SELECT p.resume_pdf_name,f.content FROM teacher_resume_files f
            JOIN profiles p ON p.account_id=f.account_id JOIN accounts a ON a.id=p.account_id
            WHERE f.file_id=? AND a.email_verified AND a.business_identity='TUTOR'
            AND (f.account_id=? OR (? AND p.is_public))
            """, (rs, n) -> new Document(rs.getString(1), rs.getBytes(2)), fileId, viewer.id(), "REQUESTER".equals(viewer.identity()));
        if (files.isEmpty()) throw new ApiError(404, "NOT_FOUND", "附件已移除或当前不可查看");
        return files.getFirst();
    }
}
