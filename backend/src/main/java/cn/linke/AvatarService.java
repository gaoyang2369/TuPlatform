package cn.linke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
class AvatarService {
    static final String URL_PREFIX = "/api/v1/avatars/";
    private final Path directory;
    private final AuthService auth;
    private final JdbcTemplate db;

    AvatarService(AuthService auth, JdbcTemplate db, @Value("${app.avatar-directory}") String directory) {
        this.auth = auth;
        this.db = db;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Transactional
    public String upload(String session, MultipartFile file) {
        Account actor = auth.current(session, true);
        byte[] image = AvatarImage.jpeg(file);
        String filename = UUID.randomUUID() + ".jpg";
        String url = URL_PREFIX + filename;
        Path destination = directory.resolve(filename);
        try {
            Files.createDirectories(directory);
            // Create exclusively before registering cleanup so we only ever delete our own file.
            Files.createFile(destination);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) remove(destination);
                    if (status == STATUS_COMMITTED && actor.avatarUrl() != null) {
                        String previous = actor.avatarUrl();
                        if (previous.startsWith(URL_PREFIX) && previous.substring(URL_PREFIX.length()).matches("[0-9a-f-]{36}\\.jpg"))
                            remove(directory.resolve(previous.substring(URL_PREFIX.length())));
                    }
                }
            });
            Files.write(destination, image, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new ApiError(503, "AVATAR_STORAGE_UNAVAILABLE", "头像暂时无法保存，请稍后重试");
        }
        db.update("UPDATE profiles SET avatar_url=?, updated_at=now() WHERE account_id=?", url, actor.id());
        return url;
    }

    private void remove(Path file) {
        try { Files.deleteIfExists(file); }
        catch (IOException e) { LoggerFactory.getLogger(AvatarService.class).warn("Could not remove obsolete avatar", e); }
    }

    public byte[] read(String session, UUID id) throws IOException {
        Account viewer = auth.current(session, false);
        String filename = id + ".jpg";
        boolean visible = Boolean.TRUE.equals(db.queryForObject("""
            SELECT EXISTS (SELECT 1 FROM profiles p JOIN accounts a ON a.id=p.account_id
              WHERE p.avatar_url=? AND (p.account_id=?
                OR (?='REQUESTER' AND p.is_public AND a.email_verified AND a.business_identity='TUTOR')
                OR EXISTS (SELECT 1 FROM match_relationships m WHERE
                  (m.requester_id=? AND m.tutor_id=p.account_id) OR (m.tutor_id=? AND m.requester_id=p.account_id))))
            """, Boolean.class, URL_PREFIX + filename, viewer.id(), viewer.identity(), viewer.id(), viewer.id()));
        if (!visible) throw missing();
        try { return Files.readAllBytes(directory.resolve(filename)); }
        catch (NoSuchFileException e) { throw missing(); }
    }

    private ApiError missing() { return new ApiError(404, "NOT_FOUND", "头像不存在或不可查看"); }
}
