import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

// Source-file launcher, using the same JDBC driver and DB settings as the backend.
class PilotMaintenance {
    static String setting(Properties file, String key, String fallback) {
        return System.getenv().getOrDefault(key,file.getProperty(key,fallback));
    }

    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("请通过维护脚本指定操作、参数及配置路径");
            boolean reset = args[0].equals("reset-demo-data") && args[1].equals("--confirm");
            if (!reset && !(args[0].equals("create-admin") && args[1].matches("[^@\\s]+@[^@\\s]+")))
                throw new IllegalArgumentException("初始化管理员需提供邮箱，清理模拟数据需显式传入 --confirm");
            Properties file = new Properties();
            if (Files.exists(Path.of(args[2]))) try (var reader=Files.newBufferedReader(Path.of(args[2]))) { file.load(reader); }
            String url = setting(file,"DB_URL","jdbc:postgresql://"+setting(file,"DB_HOST","localhost")+":"+
                setting(file,"DB_PORT","5432")+"/"+setting(file,"DB_NAME","TuPlatform")+"?sslmode="+setting(file,"DB_SSLMODE","require"));
            try (var db = DriverManager.getConnection(url, setting(file,"DB_USERNAME",setting(file,"DB_USER","")), setting(file,"DB_PASSWORD",""))) {
                db.setAutoCommit(false);
                try (var sql=db.createStatement()) { sql.execute("SET LOCAL lock_timeout='5s'"); }
                if (reset) reset(db); else promote(db,args[1]);
                db.commit();
                System.out.println("维护已完成，目标数据库为 " + db.getCatalog());
            }
        } catch (SQLException e) {
            System.err.println("数据库操作失败（SQLSTATE " + e.getSQLState() + "），事务未提交。");
            System.exit(1);
        } catch (Exception e) { System.err.println(e.getMessage()); System.exit(1); }
    }

    static void promote(Connection db, String email) throws SQLException {
        try (var sql=db.prepareStatement("SELECT id,operator_granted FROM accounts WHERE email_key=? AND email_verified AND NOT is_frozen FOR UPDATE")) {
            sql.setString(1,email.trim().toLowerCase(Locale.ROOT));
            try (var row=sql.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("未找到已验证邮箱且可用的账号");
                if (row.getBoolean(2)) return;
                for (String query : List.of("UPDATE accounts SET operator_granted=true WHERE id=?", "DELETE FROM auth_sessions WHERE account_id=?")) {
                    try (var update=db.prepareStatement(query)) { update.setObject(1,row.getObject(1)); update.executeUpdate(); }
                }
            }
        }
    }

    static void reset(Connection db) throws Exception {
        try (var sql=db.createStatement()) {
            sql.execute("SELECT pg_advisory_xact_lock(20261111,136)");
            sql.execute("CREATE TEMP TABLE fixtures(id uuid PRIMARY KEY) ON COMMIT DROP");
        }
        try (var input=new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             var lookup=db.prepareStatement("SELECT id FROM accounts WHERE id=? AND account_key=? AND email_key=? AND NOT operator_granted FOR UPDATE");
             var insert=db.prepareStatement("INSERT INTO fixtures VALUES (?)")) {
            for (String name : input.lines().toList()) {
                if (!name.matches("lk_demo_[rt][0-9]{3}")) throw new IllegalArgumentException("种子清单含无法识别的账号名");
                UUID id=UUID.nameUUIDFromBytes(("linke-demo-20261111-v1:"+name).getBytes(StandardCharsets.UTF_8));
                lookup.setObject(1,id); lookup.setString(2,name); lookup.setString(3,name+"@seed.linke.invalid");
                try (var row=lookup.executeQuery()) {
                    if (row.next()) { insert.setObject(1,id); insert.executeUpdate(); }
                }
            }
        }
        try (var sql=db.createStatement()) {
            try (var row=sql.executeQuery("""
                SELECT 1 FROM match_relationships WHERE
                  (requester_id IN (SELECT id FROM fixtures)) <> (tutor_id IN (SELECT id FROM fixtures)) LIMIT 1
                """)) {
                if (row.next()) throw new IllegalStateException("检测到模拟账号与其他账号存在撮合，已取消整批清理");
            }
            sql.executeUpdate("""
                DELETE FROM match_relationships WHERE requester_id IN (SELECT id FROM fixtures)
                    AND tutor_id IN (SELECT id FROM fixtures)
                """);
            // Round-scoped reviews cascade with matches. Account events cascade only for these verified fixture IDs.
            sql.executeUpdate("DELETE FROM analytics_events WHERE meta_json->>'fixture_batch'='linke-demo-20261111-v1'");
            sql.executeUpdate("DELETE FROM accounts WHERE id IN (SELECT id FROM fixtures)");
        }
    }
}
