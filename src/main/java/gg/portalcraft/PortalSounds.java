package gg.portalcraft;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;

/** Проигрывает WAV из игры Portal через Java Sound. */
public final class PortalSounds {
    private record Hint(String exact, String... contains) {}

    private static final Map<String, Hint> HINTS = Map.of(
            "blue", new Hint("sound/weapons/portalgun/portalgun_shoot_blue1.wav", "portalgun", "shoot", "blue"),
            "orange", new Hint("sound/weapons/portalgun/portalgun_shoot_red1.wav", "portalgun", "shoot", "red"),
            "invalid", new Hint("sound/weapons/portalgun/portal_invalid_surface3.wav", "invalid_surface"),
            "fizzle", new Hint("sound/weapons/portalgun/portal_fizzle2.wav", "portal_fizzle"),
            "enter", new Hint("sound/player/portal_enter1.wav", "portal_enter"),
            "exit", new Hint("sound/player/portal_exit1.wav", "portal_exit"),
            "hud_on", new Hint("sound/common/wpn_hudon.wav", "wpn_hudon"),
            "hud_off", new Hint("sound/common/wpn_hudoff.wav", "wpn_hudoff"));

    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();

    private PortalSounds() {}

    public static void play(String id, float volume) {
        if (!PortalAssets.ready() || volume <= 0f) return;
        Thread t = new Thread(() -> playNow(id, volume), "PortalCraft-sound");
        t.setDaemon(true);
        t.start();
    }

    private static void playNow(String id, float volume) {
        try {
            byte[] wav = CACHE.computeIfAbsent(id, k -> {
                Hint h = HINTS.get(k);
                if (h == null) return new byte[0];
                String path = PortalAssets.find(h.exact(), ".wav", h.contains());
                byte[] d = path == null ? null : PortalAssets.read(path);
                return d == null ? new byte[0] : d;
            });
            if (wav.length == 0) return;
            AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl g = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                float db = (float) (20.0 * Math.log10(Math.max(volume, 0.0001f)));
                g.setValue(Math.max(g.getMinimum(), Math.min(g.getMaximum(), db)));
            }
            clip.addLineListener(e -> {
                if (e.getType() == LineEvent.Type.STOP) clip.close();
            });
            clip.start();
        } catch (Exception e) {
            PortalServer.LOG.debug("sound {}: {}", id, e.toString());
        }
    }
}
