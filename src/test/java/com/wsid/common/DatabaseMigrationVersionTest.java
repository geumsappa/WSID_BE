package com.wsid.common;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.regex.Pattern;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class DatabaseMigrationVersionTest {
    @Test void migrationVersionsMustBeUniqueBeforeConnectingToAnyDatabase() throws Exception {
        var resources = new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/V*__*.sql");
        assertTrue(resources.length > 0, "Migration resources must be available");
        var pattern = Pattern.compile("^V([0-9]+(?:[._][0-9]+)*)__.+\\.sql$");
        var versions = new HashMap<MigrationVersion, String>();
        for (var resource : resources) {
            var name = resource.getFilename();
            var match = pattern.matcher(name);
            assertTrue(match.matches(), () -> "Invalid migration filename: " + name);
            var version = MigrationVersion.fromVersion(match.group(1));
            var previous = versions.putIfAbsent(version, name);
            assertNull(previous, () -> "Duplicate migration version: " + previous + " / " + name);
        }
    }
}
