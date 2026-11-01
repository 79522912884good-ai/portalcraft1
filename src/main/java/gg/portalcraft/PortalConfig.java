package gg.portalcraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;

/** Настройки в config/portalcraft.properties: portal_dir, enabled, crosshair_blue, crosshair_orange. */
public final class PortalConfig {
    private static final Properties P = new Properties();
    private static Path file;

    private PortalConfig() {}

    public static void load() {
        file = FabricLoader.getInstance().getConfigDir().resolve("portalcraft.properties");
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                P.load(r);
            } catch (IOException ignored) {
            }
        }
    }

    public static String get(String key, String def) { return P.getProperty(key, def); }

    public static boolean enabled() { return Boolean.parseBoolean(P.getProperty("enabled", "true")); }

    public static void set(String key, String value) {
        P.setProperty(key, value);
        if (file == null) return;
        try (Writer w = Files.newBufferedWriter(file)) {
            P.store(w, "PortalCraft");
        } catch (IOException ignored) {
        }
    }
}
