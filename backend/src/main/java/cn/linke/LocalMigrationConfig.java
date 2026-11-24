package cn.linke;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Only enabled by the Windows packaged launcher. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("app.local-launch.database")
class LocalMigrationConfig {
    private static final Logger log = LoggerFactory.getLogger(LocalMigrationConfig.class);

    @Bean
    FlywayMigrationStrategy localMigrations(@Value("${app.local-launch.database}") String expected,
            @Value("${app.local-launch.approve-through:}") String approved) {
        return flyway -> {
            try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                if (!Set.of("tuplatform", "tuplatform_test").contains(expected)
                        || !expected.equals(connection.getCatalog())) {
                    throw blocked("实际连接的库与指定的 " + expected + " 不一致，请检查数据库配置。");
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("无法确认迁移目标库。", ex);
            }
            var pending = flyway.info().pending();
            if (pending.length > 0 && expected.equals("tuplatform")) {
                var scripts = Arrays.stream(pending).map(MigrationInfo::getScript).collect(Collectors.joining(", "));
                var limit = approved.isEmpty() ? null : MigrationVersion.fromVersion(approved);
                if (Arrays.stream(pending).anyMatch(m -> m.getVersion() == null)) {
                    throw blocked("共享库含不受版本上限覆盖的迁移，请单独审核：" + scripts);
                }
                if (limit == null || Arrays.stream(pending).anyMatch(m -> m.getVersion().compareTo(limit) > 0)) {
                    var highest = Arrays.stream(pending).map(MigrationInfo::getVersion).max(MigrationVersion::compareTo).orElseThrow();
                    throw blocked("共享库待执行：" + scripts + "。审核后运行 .\\local.cmd -ApproveThrough V" + highest);
                }
            }
            flyway.migrate();
        };
    }

    private static IllegalStateException blocked(String reason) {
        log.error("[local-migration] {}", reason);
        return new IllegalStateException(reason);
    }
}
