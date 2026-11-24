import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Offline operator maintenance. No HTTP bypass, email delivery or schema changes. */
class LocalOperator {
    static final SecureRandom RANDOM = new SecureRandom();
    static String token(int count) { byte[] b=new byte[count];RANDOM.nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    static String hash(String password) throws Exception {
        byte[] salt=new byte[16];RANDOM.nextBytes(salt);
        var spec=new PBEKeySpec(password.toCharArray(),salt,600000,256);
        try {return "pbkdf2-sha256$600000$"+Base64.getEncoder().encodeToString(salt)+"$"+Base64.getEncoder().encodeToString(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}
        finally {spec.clearPassword();}
    }
    static String value(Properties cfg,String name,String fallback) {String v=System.getenv(name);return v==null?cfg.getProperty(name,fallback):v;}
    public static void main(String[] args) throws Exception {
        if(args.length!=4 || !Set.of("create","reset").contains(args[0]) || !Set.of("tuplatform","tuplatform_test").contains(args[3]))
            throw new IllegalArgumentException("Expected create|reset, env path, credential path, expected database");
        Properties cfg=new Properties();try(var r=Files.newBufferedReader(Path.of(args[1]))){cfg.load(r);}
        String url=value(cfg,"DB_URL",null);
        if(url==null) url="jdbc:postgresql://"+value(cfg,"DB_HOST","localhost")+":"+value(cfg,"DB_PORT","5432")+"/"+value(cfg,"DB_NAME","tuplatform")+"?sslmode="+value(cfg,"DB_SSLMODE","require");
        Properties auth=new Properties();auth.setProperty("user",value(cfg,"DB_USERNAME",value(cfg,"DB_USER","")));auth.setProperty("password",value(cfg,"DB_PASSWORD",""));auth.setProperty("connectTimeout","5");auth.setProperty("socketTimeout","15");
        Path credentials=Path.of(args[2]).toAbsolutePath(),pending=credentials.resolveSibling(credentials.getFileName()+".pending.local");
        String account="linke_operator",marker=account+"@operators.linke.invalid",password=token(24);
        try(Connection db=DriverManager.getConnection(url,auth)) {
            db.setAutoCommit(false);
            try(var s=db.createStatement();var r=s.executeQuery("SELECT current_database()")){r.next();if(!args[3].equals(r.getString(1)))throw new IllegalStateException("Unexpected database; no writes performed");}
            try(var s=db.createStatement();var r=s.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success AND version IN ('1','2','3','4','5')")){r.next();if(r.getInt(1)!=5)throw new IllegalStateException("Approved V1-V5 must already be applied; no migration performed");}
            UUID id=null;
            try(var s=db.prepareStatement("SELECT id,operator_granted,email,email_verified FROM accounts WHERE account_key=? FOR UPDATE")) {
                s.setString(1,account);try(var r=s.executeQuery()){if(r.next()){
                    if(!r.getBoolean(2)||!marker.equals(r.getString(3))||!r.getBoolean(4))throw new IllegalStateException("Account name is already in use; no account overwritten");
                    id=r.getObject(1,UUID.class);
                }}
            }
            if(id!=null && args[0].equals("create")){db.rollback();System.out.println("Operator already exists; password unchanged. Use explicit ResetOperator if needed.");return;}
            if(id==null && args[0].equals("reset"))throw new IllegalStateException("Operator does not exist; start normally to create it");
            if(Files.exists(pending))throw new IllegalStateException("Pending credential handoff exists; retain it and resolve before retrying");
            if(id==null && Files.exists(credentials))throw new IllegalStateException("Existing credential file retained; resolve it before creating an account");
            if(id==null){
                id=UUID.randomUUID();
                try(var s=db.prepareStatement("INSERT INTO accounts(id,account,account_key,email,email_key,password_hash,adult_confirmed_at,email_verified,operator_granted,registration_flow_id,registration_context_hash) VALUES (?,?,?,?,?,?,now(),true,true,?,?)")){
                    s.setObject(1,id);s.setString(2,account);s.setString(3,account);s.setString(4,marker);s.setString(5,marker);s.setString(6,hash(password));s.setObject(7,UUID.randomUUID());s.setString(8,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token(32).getBytes(StandardCharsets.UTF_8))));s.executeUpdate();
                }
                try(var s=db.prepareStatement("INSERT INTO profiles(account_id,display_name) VALUES (?,?)")){s.setObject(1,id);s.setString(2,"运营");s.executeUpdate();}
            }else{
                try(var s=db.prepareStatement("UPDATE accounts SET password_hash=? WHERE id=?")){s.setString(1,hash(password));s.setObject(2,id);s.executeUpdate();}
                for(String table:List.of("auth_sessions","email_tokens"))try(var s=db.prepareStatement("DELETE FROM "+table+" WHERE account_id=?")){s.setObject(1,id);s.executeUpdate();}
            }
            Files.createDirectories(credentials.getParent());
            Files.writeString(pending,"Account: "+account+System.lineSeparator()+"Password: "+password+System.lineSeparator()+"Database: "+args[3]+System.lineSeparator(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
            // Retain the pending file if commit or handoff is uncertain; never lose the only password.
            db.commit();
            Files.move(pending,credentials,StandardCopyOption.REPLACE_EXISTING);
            System.out.println("Operator ready. Credentials saved locally; no password printed.");
        }catch(Exception ignored){
            // Do not print JDBC URLs, login names, configuration values or generated credentials.
            System.err.println("Operator maintenance failed. Check database availability, account collision, or a pending credential handoff. No existing unrelated account was overwritten.");
            System.exit(1);
        }
    }
}
