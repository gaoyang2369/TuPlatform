package cn.linke;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class AuthService {
    private final JdbcTemplate db;
    private final Secrets secrets;
    private final MailGateway mail;
    private final String dummyPassword;
    AuthService(JdbcTemplate db, Secrets secrets, MailGateway mail) {
        this.db = db; this.secrets = secrets; this.mail = mail;
        this.dummyPassword = secrets.password(secrets.token());
    }
    private Account map(ResultSet r, int row) throws SQLException {
        return new Account(r.getObject("id", UUID.class), r.getString("account"), r.getString("email"),
            r.getString("password_hash"), r.getBoolean("email_verified"), r.getString("business_identity"),
            r.getBoolean("operator_granted"), r.getObject("registration_flow_id", UUID.class),
            r.getString("registration_context_hash"), r.getLong("version"));
    }
    private static final String ACCOUNT = "SELECT a.*, p.version FROM accounts a JOIN profiles p ON p.account_id=a.id ";
    private Account one(List<Account> rows) { return rows.isEmpty() ? null : rows.getFirst(); }
    private boolean managedOperator(Account a) { return a.operator() && a.email().endsWith("@operators.linke.invalid"); }
    public Me me(Account a) { return new Me(a.id(), a.account(), managedOperator(a) ? "" : a.email(), !managedOperator(a) && a.verified(), a.identity(), a.operator(), a.profileVersion()); }
    public Account current(String session, boolean lock) {
        if (session == null || session.length() != 43) throw new ApiError(401, "LOGIN_REQUIRED", "请先登录");
        Account a = one(db.query(ACCOUNT + "WHERE a.email_verified AND EXISTS (SELECT 1 FROM auth_sessions s WHERE s.account_id=a.id AND s.token_hash=? AND s.expires_at>now())" + (lock ? " FOR UPDATE OF a,p" : ""), this::map, secrets.digest(session)));
        if (a == null) throw new ApiError(401, "LOGIN_REQUIRED", "登录已失效，请重新登录");
        if (lock && !Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM auth_sessions WHERE token_hash=? AND account_id=? AND expires_at>clock_timestamp())", Boolean.class, secrets.digest(session), a.id())))
            throw new ApiError(401, "LOGIN_REQUIRED", "登录已失效，请重新登录");
        return a;
    }
    public Account business(String session, String identity) {
        Account a = current(session, true);
        if (a.identity() == null) throw new ApiError(403, "IDENTITY_REQUIRED", "请先确认一种业务身份");
        if (identity != null && !identity.equals(a.identity())) throw new ApiError(403, "IDENTITY_NOT_ALLOWED", "当前身份不能执行此操作");
        return a;
    }
    public Registration register(RegisterBody body) {
        String key = Rules.account(body.account()); String email = Rules.email(body.email()); Rules.password(body.password());
        if (!Boolean.TRUE.equals(body.adultConfirmed())) throw ApiError.invalid("adultConfirmed", "请主动确认本人已年满18岁");
        // Allow reuse only once the ORIGINAL unverified registration has reached seven days.
        db.update("DELETE FROM accounts WHERE NOT email_verified AND created_at<=now()-interval '7 days' AND (account_key=? OR email_key=?)", key, email);
        UUID id = UUID.randomUUID(), flow = UUID.randomUUID(); String context = secrets.token();
        db.update("INSERT INTO accounts(id,account,account_key,email,email_key,password_hash,adult_confirmed_at,registration_flow_id,registration_context_hash) VALUES (?,?,?,?,?,?,now(),?,?)",
            id, body.account(), key, body.email(), email, secrets.password(body.password()), flow, secrets.digest(context));
        db.update("INSERT INTO profiles(account_id) VALUES (?)", id);
        return new Registration(flow, context, verification(id, body.email()));
    }
    private boolean verification(UUID id, String email) {
        String token = secrets.token();
        db.update("INSERT INTO email_tokens(token_hash,account_id,purpose,expires_at) SELECT ?,id,'VERIFY',least(now()+interval '24 hours',created_at+interval '7 days') FROM accounts WHERE id=?", secrets.digest(token), id);
        return mail.send(email, token, false);
    }
    private UUID tokenOwner(String token, String purpose) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new ApiError(422, "LINK_INVALID", "链接已失效或已使用，请重新申请");
        List<UUID> rows = db.query("SELECT account_id FROM email_tokens WHERE token_hash=? AND purpose=? AND consumed_at IS NULL AND expires_at>now()", (r, i) -> r.getObject(1, UUID.class), secrets.digest(token), purpose);
        if (rows.isEmpty()) throw new ApiError(422, "LINK_INVALID", "链接已失效或已使用，请重新申请");
        return rows.getFirst();
    }
    private Account tokenAccount(String token, String purpose) {
        UUID id = tokenOwner(token, purpose);
        Account a = one(db.query(ACCOUNT + "WHERE a.id=? FOR UPDATE OF a", this::map, id));
        // Recheck after obtaining the account lock; concurrent confirmation/reset must not reuse the token.
        tokenOwner(token, purpose);
        if (a == null) throw new ApiError(422, "LINK_INVALID", "链接已失效，请重新申请");
        if (purpose.equals("VERIFY")) {
            Boolean active = db.queryForObject("SELECT NOT email_verified AND created_at>now()-interval '7 days' FROM accounts WHERE id=?", Boolean.class, id);
            if (!Boolean.TRUE.equals(active)) throw new ApiError(422, "LINK_INVALID", "链接已失效或已使用，请重新注册或登录");
        } else if (!a.verified()) throw new ApiError(422, "LINK_INVALID", "链接已失效，请重新申请");
        return a;
    }
    private boolean contextMatches(Account a, String context) {
        return context != null && context.length() == 43 && java.security.MessageDigest.isEqual(a.contextHash().getBytes(java.nio.charset.StandardCharsets.US_ASCII), secrets.digest(context).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    public Map<String, Object> inspect(String token, String context) {
        try {
            Account a = tokenAccount(token, "VERIFY");
            return Map.of("valid", true, "requiresRegistrationPassword", !contextMatches(a, context));
        } catch (ApiError e) {
            if (!e.code.equals("LINK_INVALID")) throw e;
            return Map.of("valid", false, "requiresRegistrationPassword", true);
        }
    }
    public void verify(VerifyBody body, String context) {
        Account a = tokenAccount(body.token(), "VERIFY");
        if (!contextMatches(a, context) && !secrets.matches(body.registrationPassword(), a.passwordHash()))
            throw ApiError.invalid("registrationPassword", "请核对注册时设置的密码");
        db.update("UPDATE accounts SET email_verified=true WHERE id=?", a.id());
        consume(a.id(), "VERIFY");
    }
    private void consume(UUID id, String purpose) {
        db.update("UPDATE email_tokens SET consumed_at=now() WHERE account_id=? AND purpose=? AND consumed_at IS NULL", id, purpose);
    }
    public boolean resend(String flowId, String context) {
        UUID flow;
        try { flow = UUID.fromString(flowId); } catch (Exception e) { throw new ApiError(422, "REGISTRATION_CONTEXT_INVALID", "请在原注册页面重发，或重新注册"); }
        Account a = one(db.query(ACCOUNT + "WHERE registration_flow_id=? AND NOT email_verified AND created_at>now()-interval '7 days' FOR UPDATE OF a", this::map, flow));
        if (a == null || !contextMatches(a, context)) throw new ApiError(422, "REGISTRATION_CONTEXT_INVALID", "请在原注册页面重发，或重新注册");
        return verification(a.id(), a.email());
    }
    public LoginResult login(LoginBody body) {
        String key = body.account() == null ? "" : body.account().toLowerCase(Locale.ROOT);
        Account a = one(db.query(ACCOUNT + "WHERE account_key=? FOR UPDATE OF a", this::map, key));
        boolean valid = secrets.matches(body.password(), a == null ? dummyPassword : a.passwordHash());
        if (a == null || !valid) throw new ApiError(401, "LOGIN_FAILED", "账号或密码不正确");
        if (!a.verified()) throw new ApiError(403, "EMAIL_VERIFICATION_REQUIRED", "请先验证注册邮箱；可在原注册页面重新发送邮件");
        String session = secrets.token();
        db.update("INSERT INTO auth_sessions(token_hash,account_id,expires_at) VALUES (?,?,now()+interval '7 days')", secrets.digest(session), a.id());
        return new LoginResult(me(a), session);
    }
    public void logout(String session) {
        if (session != null && session.length() == 43) db.update("DELETE FROM auth_sessions WHERE token_hash=?", secrets.digest(session));
    }
    public void forgot(String email) {
        String key = Rules.email(email);
        // Disabled transport is reported uniformly for all addresses; no account enumeration.
        if (!mail.enabled()) throw new ApiError(503, "MAIL_UNAVAILABLE", "邮件服务尚不可用，请稍后重试");
        Account a = one(db.query(ACCOUNT + "WHERE email_key=? AND email_verified FOR UPDATE OF a", this::map, key));
        if (a == null || managedOperator(a)) return;
        String token = secrets.token();
        db.update("INSERT INTO email_tokens(token_hash,account_id,purpose,expires_at) VALUES (?,?,'RESET',now()+interval '30 minutes')", secrets.digest(token), a.id());
        // Always return the same accepted response, including transport failures for known addresses.
        mail.resetAfterCommit(a.email(), token);
    }
    public void reset(ResetBody body) {
        Rules.password(body.newPassword());
        Account a = tokenAccount(body.token(), "RESET");
        db.update("UPDATE accounts SET password_hash=? WHERE id=?", secrets.password(body.newPassword()), a.id());
        consume(a.id(), "RESET");
        db.update("DELETE FROM auth_sessions WHERE account_id=?", a.id());
    }
    public Me identity(String session, IdentityBody body) {
        if (!java.util.Set.of("REQUESTER", "TUTOR").contains(body.identity() == null ? "" : body.identity())) throw ApiError.invalid("identity", "请选择需求方或老师");
        Account a = current(session, true);
        if (a.identity() != null && !a.identity().equals(body.identity())) throw new ApiError(409, "IDENTITY_ALREADY_SELECTED", "业务身份已确认，首版暂不支持修改，请重新读取账号状态");
        db.update("UPDATE accounts SET business_identity=? WHERE id=?", body.identity(), a.id());
        return me(current(session, false));
    }
}
