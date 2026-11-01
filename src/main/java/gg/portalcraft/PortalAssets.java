package gg.portalcraft;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Доступ к файлам игры Portal. Всё читается из Steam-установки игрока во время работы;
 * в мод ничего из Portal не вложено.
 */
public final class PortalAssets {
    private static final List<Vpk> PAKS = new ArrayList<>();
    private static volatile boolean ready;
    private static Path root;

    private PortalAssets() {}

    public static boolean ready() { return ready; }
    public static int count() { return PAKS.size(); }
    public static Path root() { return root; }

    public static synchronized void init(Path portalDir) throws IOException {
        PAKS.clear();
        root = portalDir;
        // Сначала portal/, потом hl2/ (как порядок поиска Source).
        for (String sub : new String[] {"portal", "hl2"}) {
            Path d = portalDir.resolve(sub);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.list(d)) {
                s.filter(p -> p.getFileName().toString().endsWith("_dir.vpk")).sorted().forEach(p -> {
                    try {
                        PAKS.add(Vpk.open(p));
                    } catch (IOException e) {
                        PortalServer.LOG.warn("Не удалось открыть {}: {}", p, e.toString());
                    }
                });
            }
        }
        ready = !PAKS.isEmpty();
    }

    /** Точный путь внутри VPK (нижний регистр, '/'), например "sound/weapons/portalgun/x.wav". */
    public static byte[] read(String path) {
        String key = path.toLowerCase().replace('\\', '/');
        for (Vpk v : PAKS) {
            Vpk.Entry e = v.entries.get(key);
            if (e != null) {
                try {
                    return v.read(e);
                } catch (IOException ex) {
                    PortalServer.LOG.warn("Ошибка чтения {}: {}", key, ex.toString());
                }
            }
        }
        return null;
    }

    /** Если точного пути нет, ищет первый файл нужного расширения, содержащий все подстроки. */
    public static String find(String exact, String ext, String... contains) {
        String key = exact == null ? null : exact.toLowerCase();
        for (Vpk v : PAKS) {
            if (key != null && v.entries.containsKey(key)) return key;
        }
        for (Vpk v : PAKS) {
            String best = null;
            for (String p : v.entries.keySet()) {
                if (!p.endsWith(ext)) continue;
                boolean ok = true;
                for (String c : contains) if (!p.contains(c)) { ok = false; break; }
                if (ok && (best == null || p.length() < best.length())) best = p;
            }
            if (best != null) return best;
        }
        return null;
    }

    public static Vtf vtf(String path) {
        byte[] data = read(path);
        if (data == null) return null;
        try {
            return Vtf.decode(data);
        } catch (IOException e) {
            PortalServer.LOG.warn("VTF {}: {}", path, e.toString());
            return null;
        }
    }

    /** Пишет список интересных файлов (HUD, портальная пушка, шрифты) для отладки и подбора путей. */
    public static void dump(Path out) {
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("Portal: " + root);
            for (Vpk v : PAKS) {
                v.entries.keySet().stream().sorted().forEach(p -> {
                    if (p.contains("crosshair") || p.contains("portalgun") || p.contains("/hud/")
                            || p.endsWith(".ttf") || p.contains("portal_enter") || p.contains("portal_exit")
                            || p.contains("wpn_hud")) w.println(p);
                });
            }
        } catch (IOException ignored) {
        }
    }
}
