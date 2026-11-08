package cn.linke;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class RateLimits {
    private final JdbcTemplate db;
    private final Secrets secrets;
    RateLimits(JdbcTemplate db, Secrets secrets) { this.db = db; this.secrets = secrets; }
    // Separate transaction: failed logins and rolled-back mutations still consume attempts.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean allow(String scope, String subject, int maximum) {
        Integer count = db.queryForObject("""
            INSERT INTO auth_rate_limits(bucket_key, attempts, window_end) VALUES (?, 1, now() + interval '15 minutes')
            ON CONFLICT (bucket_key) DO UPDATE SET
              attempts = CASE WHEN auth_rate_limits.window_end <= now() THEN 1 ELSE auth_rate_limits.attempts + 1 END,
              window_end = CASE WHEN auth_rate_limits.window_end <= now() THEN now() + interval '15 minutes' ELSE auth_rate_limits.window_end END
            RETURNING attempts
            """, Integer.class, secrets.digest(scope + ":" + subject));
        return count != null && count <= maximum;
    }
    @Scheduled(fixedDelay = 3600000)
    public void cleanup() {
        db.update("DELETE FROM auth_rate_limits WHERE window_end < now()");
        db.update("DELETE FROM auth_sessions WHERE expires_at < now()");
        db.update("DELETE FROM email_tokens WHERE expires_at < now() OR consumed_at < now() - interval '1 day'");
        db.update("DELETE FROM accounts WHERE NOT email_verified AND created_at <= now() - interval '7 days'");
        // Mutation receipts are retained with the account so retries cannot replay stale actions.
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiError.class)
    public void check(String scope, String subject, int maximum) {
        if (!allow(scope, subject, maximum)) throw new ApiError(429, "RATE_LIMITED", "操作较频繁，请15分钟后再试");
    }
}
