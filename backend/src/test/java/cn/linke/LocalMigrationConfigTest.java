package cn.linke;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalMigrationConfigTest {
    private final Flyway flyway = mock(Flyway.class, RETURNS_DEEP_STUBS);
    private final LocalMigrationConfig config = new LocalMigrationConfig();

    private void database(String name, String... versions) throws Exception {
        when(flyway.getConfiguration().getDataSource().getConnection().getCatalog()).thenReturn(name);
        var pending = new MigrationInfo[versions.length];
        for (int i = 0; i < versions.length; i++) {
            pending[i] = mock(MigrationInfo.class);
            when(pending[i].getVersion()).thenReturn(MigrationVersion.fromVersion(versions[i]));
            when(pending[i].getScript()).thenReturn("V" + versions[i] + "__example.sql");
        }
        when(flyway.info().pending()).thenReturn(pending);
    }

    @Test void sharedApprovalCoversTheWholeBatchOrNoneOfIt() throws Exception {
        database("tuplatform", "11", "12", "13");
        for (String ceiling : new String[]{"", "10", "12"}) {
            var error = assertThrows(IllegalStateException.class,
                    () -> config.localMigrations("tuplatform", ceiling).migrate(flyway));
            assertTrue(error.getMessage().contains("V11__example.sql, V12__example.sql, V13__example.sql"));
            assertTrue(error.getMessage().contains("-ApproveThrough V13"));
        }
        verify(flyway, never()).migrate();
        config.localMigrations("tuplatform", "13").migrate(flyway);
        verify(flyway).migrate();
    }

    @Test void currentSharedDatabaseNeedsNoApproval() throws Exception {
        database("tuplatform");
        config.localMigrations("tuplatform", "").migrate(flyway);
        verify(flyway).migrate();
    }

    @Test void testModeRequiresTheActualTestDatabase() throws Exception {
        database("tuplatform", "14");
        assertThrows(IllegalStateException.class, () -> config.localMigrations("tuplatform_test", "").migrate(flyway));
        verify(flyway, never()).migrate();
        database("tuplatform_test", "14");
        config.localMigrations("tuplatform_test", "").migrate(flyway);
        verify(flyway).migrate();
    }
}
