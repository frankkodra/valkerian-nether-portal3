package Portal.code;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class Logger {
    // Static public variables
    public static boolean newFile = false;
    public static int currentFileNumber = 1;
    private static PrintWriter currentWriter = null;
    private static final String LOG_FOLDER = "logs";
    private static final String CONFIG_FILE = "valkerian_nether_portal.toml";
    private static final String FILE_PREFIX = "ship-teleport-";
    private static final String FILE_EXTENSION = ".log";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FILE_NUMBER_PATTERN = Pattern.compile(FILE_PREFIX + "(\\d+)" + FILE_EXTENSION);

    // Configuration variables
    private static boolean sendLogsToAllPlayers = false;
    private static boolean createLogFiles = false;

    // Static initializer - runs when class is first loaded
    static {
        initializeConfig();
        initializeLogger();
    }

    private static void initializeConfig() {
        try {
            Path configDir = FMLPaths.CONFIGDIR.get();
            Path configFile = configDir.resolve(CONFIG_FILE);

            // Create config file if it doesn't exist
            if (!Files.exists(configFile)) {
                Files.createDirectories(configDir);
                String defaultConfig =
                        "# Valkerian Nether Portal Configuration\n" +
                                "sendLogsToAllPlayers=false\n" +
                                "createLogFiles=false\n";

                Files.write(configFile, defaultConfig.getBytes());
                System.out.println("[Portal Skies] Created default config file: " + configFile);
            }

            // Read config file
            loadConfig(configFile);

        } catch (Exception e) {
            System.err.println("[Portal Skies] Failed to initialize config: " + e.getMessage());
            // Use default values if config fails
            sendLogsToAllPlayers = false;
            createLogFiles = false;
        }
    }

    private static void loadConfig(Path configFile) {
        try {
            String content = new String(Files.readAllBytes(configFile));
            String[] lines = content.split("\n");

            for (String line : lines) {
                line = line.trim();
                // Skip comments and empty lines
                if (line.startsWith("#") || line.isEmpty()) continue;

                String[] parts = line.split("=");
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();

                    switch (key) {
                        case "sendLogsToAllPlayers":
                            sendLogsToAllPlayers = Boolean.parseBoolean(value);
                            break;
                        case "createLogFiles":
                            createLogFiles = Boolean.parseBoolean(value);
                            break;
                    }
                }
            }

            System.out.println("[Portal Skies] Config loaded - sendLogsToAllPlayers: " + sendLogsToAllPlayers +
                    ", createLogFiles: " + createLogFiles);

        } catch (Exception e) {
            System.err.println("[Portal Skies] Failed to load config: " + e.getMessage());
            // Use default values if loading fails
            sendLogsToAllPlayers = false;
            createLogFiles = false;
        }
    }

    private static void initializeLogger() {
        try {
            // Only initialize file logging if enabled in config
            if (createLogFiles) {
                // Ensure logs directory exists
                Files.createDirectories(Paths.get(LOG_FOLDER));

                // Find the highest existing file number
                currentFileNumber = findHighestFileNumber();

                // Create or open the current log file
                openCurrentLogFile();
            } else {
                System.out.println("[Portal Skies] File logging is disabled in config");
            }

        } catch (Exception e) {
            System.err.println("[Portal Skies] Failed to initialize Logger: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static int findHighestFileNumber() {
        try {
            return Files.list(Paths.get(LOG_FOLDER))
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .map(fileName -> {
                        Matcher matcher = FILE_NUMBER_PATTERN.matcher(fileName);
                        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
                    })
                    .max(Comparator.naturalOrder())
                    .orElse(0); // Returns 0 if no files found, so we start at 1

        } catch (Exception e) {
            System.err.println("[Portal Skies] Error scanning log files: " + e.getMessage());
            return 0;
        }
    }

    private static void openCurrentLogFile() {
        try {
            // Close previous writer if exists
            if (currentWriter != null) {
                currentWriter.close();
            }

            String filename = FILE_PREFIX + currentFileNumber + FILE_EXTENSION;
            File logFile = new File(LOG_FOLDER, filename);

            // Create writer in append mode
            currentWriter = new PrintWriter(new FileWriter(logFile, true));

        } catch (Exception e) {
            System.err.println("[Portal Skies] Failed to open log file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Main public static method
    public static void sendMessage(String message, boolean outputToPlayers) {
        // Handle new file request (only if file logging is enabled)
        if (newFile && createLogFiles) {
            currentFileNumber++;
            openCurrentLogFile();
            newFile = false;
        }

        // Write to log file only if enabled in config
        if (createLogFiles) {
            writeToLogFile(message);
        }

        // Output to players only if enabled in config AND requested by caller
        if (outputToPlayers && sendLogsToAllPlayers) {
            sendToAllPlayers(message);
        }

        // Always log to console for debugging
        System.out.println("[Portal Skies] " + message);
    }

    private static void writeToLogFile(String message) {
        if (currentWriter != null && createLogFiles) {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
            currentWriter.println("[" + timestamp + "] " + message);
            currentWriter.flush(); // Ensure it's written immediately
        }
    }

    private static void sendToAllPlayers(String message) {
        try {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                // Get all world levels and send message to all players in each one
                for (ServerLevel level : server.getAllLevels()) {
                    for (ServerPlayer player : level.players()) {
                        try {
                            player.sendSystemMessage(Component.literal(message));
                        } catch (Exception e) {
                            System.out.println("[Portal Skies] Failed to send message to player: " + e.getMessage());
                        }
                    }
                }

                // Also log to console for visibility
                System.out.println("[Portal Skies] [PLAYER-MSG] " + message);
            } else {
                // Fallback if server is not available
                System.out.println("[Portal Skies] [PLAYER-MSG-SERVER-NULL] " + message);
            }
        } catch (Exception e) {
            // Final fallback
            System.out.println("[Portal Skies] [PLAYER-MSG-ERROR] " + message + " [Error: " + e.getMessage() + "]");
        }
    }

    // Utility method to close the logger properly
    public static void close() {
        if (currentWriter != null) {
            currentWriter.close();
            currentWriter = null;
        }
    }

    // Optional: Method to manually trigger new file
    public static void startNewLogFile() {
        newFile = true;
    }

    // Optional: Get current log file info
    public static String getCurrentLogFileName() {
        return FILE_PREFIX + currentFileNumber + FILE_EXTENSION;
    }

    // Optional: Get player count for debugging
    public static int getTotalPlayerCount() {
        try {
            var server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                int count = 0;
                for (ServerLevel level : server.getAllLevels()) {
                    count += level.players().size();
                }
                return count;
            }
        } catch (Exception e) {
            // Ignore errors in debug method
        }
        return 0;
    }

    // Getters for configuration (optional, for external access)
    public static boolean isSendLogsToAllPlayers() {
        return sendLogsToAllPlayers;
    }

    public static boolean isCreateLogFiles() {
        return createLogFiles;
    }
}
