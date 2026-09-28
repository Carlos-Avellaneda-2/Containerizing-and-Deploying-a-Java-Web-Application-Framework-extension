package co.edu.escuelaing.webframework;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConfigTest {

    @Test
    void portDefaultsTo8080WhenUnset() {
        assertEquals(8080, new Config(Map.of()).getPort());
    }

    @Test
    void portIsReadFromTheEnvironment() {
        assertEquals(9090, new Config(Map.of("PORT", "9090")).getPort());
        assertEquals(9090, new Config(Map.of("PORT", " 9090 ")).getPort());
    }

    @Test
    void blankPortFallsBackToTheDefault() {
        assertEquals(8080, new Config(Map.of("PORT", "  ")).getPort());
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "80.5", "0", "-1", "65536", "99999999999"})
    void invalidPortFailsWithAClearMessage(String value) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new Config(Map.of("PORT", value)).getPort());

        assertTrue(error.getMessage().contains("PORT"));
    }

    @Test
    void appEnvDefaultsToDevelopment() {
        Config config = new Config(Map.of());

        assertEquals("development", config.getAppEnv());
        assertTrue(config.isDevelopment());
    }

    @Test
    void productionIsNotDevelopment() {
        Config config = new Config(Map.of("APP_ENV", "production"));

        assertEquals("production", config.getAppEnv());
        assertFalse(config.isDevelopment());
    }

    @Test
    void anyOtherVariableCanBeReadWithADefault() {
        Config config = new Config(Map.of("GREETING_PREFIX", "Hola"));

        assertEquals("Hola", config.get("GREETING_PREFIX", "Hello"));
        assertEquals("Hello", config.get("MISSING", "Hello"));
        assertTrue(config.get("MISSING").isEmpty());
    }
}
