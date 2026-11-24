package cn.linke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional
class VerificationService {
    record Entry(UUID id, UUID user_id, String verify_type, String status, String reject_reason,
                 OffsetDateTime created_at, OffsetDateTime reviewed_at) {}
    record Decision(String status, String reject_reason) {}
    private static final String FIELDS = "id,user_id,verify_type,status,reject_reason,created_at,reviewed_at";
    private final AuthService auth;
    private final JdbcTemplate db;
    private final Path directory;
    private final NotificationService notifications;

    VerificationService(AuthService auth, JdbcTemplate db, @Value("${app.verification-directory}") String directory, NotificationService notifications) {
        this.notifications = notifications;
        this.auth = auth;
        this.db = db;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }
    private Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
            rs.getString("verify_type"), rs.getString("status"), rs.getString("reject_reason"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("reviewed_at", OffsetDateTime.class));
    }
    List<Entry> mine(String session) {
        var tutor = auth.business(session, "TUTOR");
        return db.query("SELECT DISTINCT ON (verify_type) " + FIELDS +
            " FROM verifications WHERE user_id=? ORDER BY verify_type,created_at DESC,id DESC", this::entry, tutor.id());
    }
    Entry submit(String session, String type, MultipartFile file) {
        var tutor = auth.business(session, "TUTOR");
        if (!"student_id".equals(type) && !"skill_cert".equals(type))
            throw ApiError.invalid("verify_type", "请选择学籍或技能证书认证");
        if (Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM verifications WHERE user_id=? AND verify_type=? AND status IN ('pending','approved'))
            """, Boolean.class, tutor.id(), type)))
            throw new ApiError(409, "VERIFICATION_EXISTS", "这类材料已在审核中或已通过，请刷新认证状态");
        VerificationProof proof = VerificationProof.from(file);
        UUID id = UUID.randomUUID();
        String name = id + "." + proof.extension();
        Path path = directory.resolve(name);
        try {
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                // On Windows the service account's private directory supplies the inherited ACL.
                Files.createDirectories(directory);
                Files.createFile(path);
            }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try { Files.deleteIfExists(path); }
                        catch (IOException e) { LoggerFactory.getLogger(VerificationService.class).error("认证材料回滚清理失败，记录 {}", id); }
                    }
                }
            });
            Files.write(path, proof.bytes(), StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new ApiError(503, "PROOF_STORAGE_UNAVAILABLE", "认证材料暂时无法保存，请稍后重试");
        }
        return db.queryForObject("INSERT INTO verifications(id,user_id,verify_type,proof_path) VALUES (?,?,?,?) RETURNING " +
            FIELDS, this::entry, id, tutor.id(), type, name);
    }
    private Account operator(String session) {
        var account = auth.current(session, true);
        if (!account.operator()) throw new ApiError(403, "OPERATOR_REQUIRED", "仅运营人员可审核认证材料");
        return account;
    }
    List<Entry> queue(String session, String status) {
        var actor = operator(session);
        if (!Set.of("pending", "approved", "rejected").contains(status))
            throw ApiError.invalid("status", "请选择有效的审核状态");
        return db.query("SELECT " + FIELDS + " FROM verifications WHERE status=? AND user_id<>? ORDER BY created_at,id",
            this::entry, status, actor.id());
    }
    Entry review(String session, UUID id, Decision decision) {
        var actor = operator(session);
        boolean rejected = "rejected".equals(decision.status());
        if (!rejected && !"approved".equals(decision.status())) throw ApiError.invalid("status", "请选择通过或驳回");
        String reason = rejected ? Rules.text("reject_reason", decision.reject_reason(), 300, true).strip() : null;
        var rows = db.query("UPDATE verifications SET status=?,reject_reason=?,reviewed_at=clock_timestamp()" +
            " WHERE id=? AND user_id<>? AND status='pending' RETURNING " + FIELDS, this::entry,
            decision.status(), reason, id, actor.id());
        if (rows.isEmpty()) throw new ApiError(409, "VERIFICATION_NOT_PENDING", "当前申请无法审核，请重新读取队列");
        notifications.verificationReviewed(rows.getFirst());
        return rows.getFirst();
    }
    VerificationProof proof(String session, UUID id) {
        var actor = auth.current(session, false);
        var paths = db.queryForList("SELECT proof_path FROM verifications WHERE id=? AND (user_id=? OR ?)",
            String.class, id, actor.id(), actor.operator());
        if (paths.isEmpty()) throw missing();
        String name = paths.getFirst();
        if (!name.matches(id + "\\.(jpg|png|webp|pdf)")) throw missing();
        Path path = directory.resolve(name);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw missing();
        try {
            return new VerificationProof(name.substring(name.lastIndexOf('.') + 1), Files.readAllBytes(path));
        } catch (IOException e) {
            throw new ApiError(503, "PROOF_STORAGE_UNAVAILABLE", "认证材料暂时无法读取");
        }
    }
    private ApiError missing() { return new ApiError(404, "NOT_FOUND", "认证材料不存在或不可查看"); }
}
