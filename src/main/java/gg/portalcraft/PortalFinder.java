package gg.portalcraft;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Находит папку установленной игры Portal (та, где лежат portal/ и hl2/). Ничего не копирует. */
public final class PortalFinder {
    private PortalFinder() {}

    public static Path find(String configured) {
        if (configured != null && !configured.isBlank()) {
            Path p = Paths.get(configured.trim());
            if (valid(p)) return p;
        }
        for (Path steam : steamRoots()) {
            for (Path lib : libraries(steam)) {
                Path p = lib.resolve("steamapps").resolve("common").resolve("Portal");
                if (valid(p)) return p;
            }
        }
        return null;
    }

    /** Папка Portal считается верной, если в ней есть portal/portal_pak_dir.vpk. */
    public static boolean valid(Path p) {
        return p != null && Files.isRegularFile(p.resolve("portal").resolve("portal_pak_dir.vpk"));
    }

    private static List<Path> steamRoots() {
        List<Path> roots = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home", "");
        if (os.contains("win")) {
            String reg = regQuery("HKCU\\Software\\Valve\\Steam", "SteamPath");
            if (reg != null) roots.add(Paths.get(reg));
            roots.add(Paths.get("C:/Program Files (x86)/Steam"));
            roots.add(Paths.get("C:/Program Files/Steam"));
        } else if (os.contains("mac")) {
            roots.add(Paths.get(home, "Library/Application Support/Steam"));
        } else {
            roots.add(Paths.get(home, ".steam/steam"));
            roots.add(Paths.get(home, ".local/share/Steam"));
            roots.add(Paths.get(home, ".var/app/com.valvesoftware.Steam/.local/share/Steam"));
        }
        return roots;
    }

    /** Читает steamapps/libraryfolders.vdf, чтобы найти игры на других дисках. */
    private static List<Path> libraries(Path steam) {
        List<Path> libs = new ArrayList<>();
        libs.add(steam);
        Path vdf = steam.resolve("steamapps").resolve("libraryfolders.vdf");
        if (!Files.isRegularFile(vdf)) return libs;
        try {
            String text = Files.readString(vdf, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(text);
            while (m.find()) libs.add(Paths.get(m.group(1).replace("\\\\", "\\")));
        } catch (Exception ignored) {
        }
        return libs;
    }

    private static String regQuery(String key, String value) {
        try {
            Process pr = new ProcessBuilder("reg", "query", key, "/v", value).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int i = line.indexOf("REG_SZ");
                    if (i >= 0) return line.substring(i + 6).trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}
