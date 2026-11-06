package com.glassstore.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DemoSeedContractTest {
    @Test
    void documentedSeedPasswordMatchesBcrypt() throws Exception {
        var encoder = new BCryptPasswordEncoder();
        var sql = Files.readString(Path.of("..", "docker", "mysql", "init", "02-seed-data.sql"));
        var hash = Pattern.compile("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}").matcher(sql);
        assertTrue(hash.find(), "Seed must contain a BCrypt password fixture");
        assertTrue(encoder.matches("123456", hash.group()), "Documented demo password must match the SQL hash");
        assertFalse(encoder.matches("wrong-password", hash.group()));
    }

    @Test
    void baselineRefusesToResetAnExistingDatabase() throws Exception {
        var sql = Files.readString(Path.of("..", "docker", "mysql", "init", "02-seed-data.sql"));
        assertTrue(sql.indexOf("CALL glassstore_assert_empty_demo()") < sql.indexOf("CREATE TABLE"));
        assertTrue(sql.contains("SIGNAL SQLSTATE '45000'"));
        assertFalse(sql.contains("`stock_quantity` = VALUES(`stock_quantity`)"));
        assertFalse(sql.contains("`password_hash` = VALUES(`password_hash`)"));
    }
}
