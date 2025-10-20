package Portal.code;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    // Existing configurable values with defaults
    public static int CHECK_INTERVAL_TICKS = 50;
    public static int PORTAL_COOLDOWN_TICKS = 100;
    public static boolean SEND_LOGS_TO_ALL_PLAYERS = false;
    public static boolean CREATE_LOG_FILES = false;
    public static boolean CONSOLE_LOGS = false; // NEW: Control console output
    public static double MIN_MOVEMENT_THRESHOLD = 0.1;
    public static long CACHE_CLEAR_INTERVAL = 30000;

    // New portal detection configuration
    public static int PORTAL_SAMPLES_PER_FACE = 3; // 3x3 grid per face
    public static int PORTAL_FACE_SKIP_INTERVAL = 2; // Check every face (0 = all faces)
    public static boolean PORTAL_ALWAYS_CHECK_FRONT_BACK = true; // Always check front and back faces

    private static final String CONFIG_FILE_NAME = "valkerian_nether_portals.toml";
    private static final String DEFAULT_CONFIG =
            "# Valkerian Nether Portals Configuration\n" +
                    "# How often to check ships for portal collisions (in ticks, 20 ticks = 1 second)\n" +
                    "# Higher values = better performance, lower values = faster detection\n" +
                    "checkShipsEveryXTicks=50\n\n" +

                    "# How long ships wait before being able to teleport again (in ticks)\n" +
                    "portalCooldownTicks=100\n\n" +

                    "# Whether to send portal teleportation logs to all players in chat\n" +
                    "sendLogsToAllPlayers=false\n\n" +

                    "# Whether to create log files in the logs/ directory\n" +
                    "createLogFiles=false\n\n" +

                    "# Whether to output logs to console (can be spammy)\n" +
                    "consoleLogs=false\n\n" +

                    "# Minimum movement speed required to check for portals (blocks/tick)\n" +
                    "# Ships moving slower than this won't be checked to improve performance\n" +
                    "minMovementThreshold=0.1\n\n" +

                    "# How often to clear portal detection cache (in milliseconds)\n" +
                    "cacheClearInterval=30000\n\n" +

                    "# Portal Detection Settings\n" +
                    "# Number of sampling points per face (creates NxN grid on each face)\n" +
                    "# Higher values = better detection, lower values = better performance\n" +
                    "portalSamplesPerFace=3\n\n" +

                    "# How many faces to skip between checks (0 = check all faces, 1 = check every other face)\n" +
                    "# Higher values = better performance, may miss some portal entries\n" +
                    "portalFaceSkipInterval=1\n\n" +

                    "# Always check front and back faces regardless of skip interval\n" +
                    "# Front/back are where ships most commonly enter/exit portals\n" +
                    "portalAlwaysCheckFrontBack=true\n"
            ;

    public static void load() {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path configFile = configDir.resolve(CONFIG_FILE_NAME);

            // Create config file if it doesn't exist
            if (!Files.exists(configFile)) {
                createDefaultConfig(configFile);
            }

            // Read and parse config
            readConfig(configFile);

            // Apply config to other classes
            applyConfig();

            // Use System.out directly since Logger might not be ready yet
            if (CONSOLE_LOGS) {
                System.out.println("[Portal Skies] Config loaded successfully");
            }

        } catch (Exception e) {
            System.out.println("[Portal Skies] Failed to load config: " + e.getMessage());
            applyDefaults();
        }
    }

    private static void createDefaultConfig(Path configFile) throws IOException {
        Files.createDirectories(configFile.getParent());
        Files.write(configFile, DEFAULT_CONFIG.getBytes());
        if (CONSOLE_LOGS) {
            System.out.println("[Portal Skies] Created default config file");
        }
    }

    private static void readConfig(Path configFile) throws IOException {
        String content = new String(Files.readAllBytes(configFile));
        String[] lines = content.split("\n");

        for (String line : lines) {
            line = line.trim();
            // Skip comments and empty lines
            if (line.startsWith("#") || line.isEmpty()) continue;

            String[] parts = line.split("=", 2); // Split on first = only
            if (parts.length == 2) {
                String key = parts[0].trim();
                String value = parts[1].trim();

                parseConfigValue(key, value);
            }
        }
    }

    private static void parseConfigValue(String key, String value) {
        try {
            switch (key) {
                case "checkShipsEveryXTicks":
                    CHECK_INTERVAL_TICKS = parseInt(value, 5);
                    break;
                case "portalCooldownTicks":
                    PORTAL_COOLDOWN_TICKS = parseInt(value, 100);
                    break;
                case "sendLogsToAllPlayers":
                    SEND_LOGS_TO_ALL_PLAYERS = parseBoolean(value, false);
                    break;
                case "createLogFiles":
                    CREATE_LOG_FILES = parseBoolean(value, false);
                    break;
                case "consoleLogs": // NEW: Parse console logs setting
                    CONSOLE_LOGS = parseBoolean(value, false);
                    break;
                case "minMovementThreshold":
                    MIN_MOVEMENT_THRESHOLD = parseDouble(value, 0.1);
                    break;
                case "cacheClearInterval":
                    CACHE_CLEAR_INTERVAL = parseLong(value, 30000);
                    break;
                // New portal detection settings
                case "portalSamplesPerFace":
                    PORTAL_SAMPLES_PER_FACE = parseInt(value, 3);
                    break;
                case "portalFaceSkipInterval":
                    PORTAL_FACE_SKIP_INTERVAL = parseInt(value, 0);
                    break;
                case "portalAlwaysCheckFrontBack":
                    PORTAL_ALWAYS_CHECK_FRONT_BACK = parseBoolean(value, true);
                    break;
                default:
                    if (CONSOLE_LOGS) {
                        System.out.println("[Portal Skies] Unknown config option: " + key);
                    }
            }
        } catch (Exception e) {
            if (CONSOLE_LOGS) {
                System.out.println("[Portal Skies] Invalid value for " + key + ": " + value);
            }
        }
    }

    private static int parseInt(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            if (CONSOLE_LOGS) {
                System.out.println("[Portal Skies] Using default value " + defaultValue + " for " + value);
            }
            return defaultValue;
        }
    }

    private static boolean parseBoolean(String value, boolean defaultValue) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        if (CONSOLE_LOGS) {
            System.out.println("[Portal Skies] Using default value " + defaultValue + " for " + value);
        }
        return defaultValue;
    }

    private static double parseDouble(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            if (CONSOLE_LOGS) {
                System.out.println("[Portal Skies] Using default value " + defaultValue + " for " + value);
            }
            return defaultValue;
        }
    }

    private static long parseLong(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            if (CONSOLE_LOGS) {
                System.out.println("[Portal Skies] Using default value " + defaultValue + " for " + value);
            }
            return defaultValue;
        }
    }

    private static void applyConfig() {
        // Use direct console output for initial config loading
        if (CONSOLE_LOGS) {
            System.out.println("[Portal Skies] Config applied:");
            System.out.println("[Portal Skies] - Check interval: " + CHECK_INTERVAL_TICKS + " ticks");
            System.out.println("[Portal Skies] - Cooldown: " + PORTAL_COOLDOWN_TICKS + " ticks");
            System.out.println("[Portal Skies] - Player logs: " + SEND_LOGS_TO_ALL_PLAYERS);
            System.out.println("[Portal Skies] - File logs: " + CREATE_LOG_FILES);
            System.out.println("[Portal Skies] - Console logs: " + CONSOLE_LOGS);
            System.out.println("[Portal Skies] - Movement threshold: " + MIN_MOVEMENT_THRESHOLD);
            System.out.println("[Portal Skies] - Cache clear: " + CACHE_CLEAR_INTERVAL + "ms");
            System.out.println("[Portal Skies] - Portal samples per face: " + PORTAL_SAMPLES_PER_FACE);
            System.out.println("[Portal Skies] - Face skip interval: " + PORTAL_FACE_SKIP_INTERVAL);
            System.out.println("[Portal Skies] - Always check front/back: " + PORTAL_ALWAYS_CHECK_FRONT_BACK);
        }
    }

    private static void applyDefaults() {
        // Reset to defaults if config loading fails
        CHECK_INTERVAL_TICKS = 50;
        PORTAL_COOLDOWN_TICKS = 100;
        SEND_LOGS_TO_ALL_PLAYERS = false;
        CREATE_LOG_FILES = false;
        CONSOLE_LOGS = false; // NEW: Default to false
        MIN_MOVEMENT_THRESHOLD = 0.1;
        CACHE_CLEAR_INTERVAL = 30000;
        PORTAL_SAMPLES_PER_FACE = 3;
        PORTAL_FACE_SKIP_INTERVAL = 1;
        PORTAL_ALWAYS_CHECK_FRONT_BACK = true;
    }

    // Utility method to reload config (can be called from commands if needed)
    public static void reload() {
        load();
    }
}