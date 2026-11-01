package gg.portalcraft;

import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Клиент: F6 переключает интерфейс Portal / Minecraft. В режиме Portal ЛКМ стреляет синим порталом,
 * ПКМ — оранжевым, R закрывает порталы, а HUD и звуки берутся из установки Portal.
 */
public final class PortalClient implements ClientModInitializer {
    private static final int HUD_YELLOW = 0xFFFFDC00; // цвет HUD в Half-Life 2 / Portal

    private static volatile boolean portalMode;
    private static volatile boolean blueOpen, orangeOpen;
    private static String missing; // причина, почему игра не найдена
    private static int uiCooldown;
    private static Identifier texBlue, texOrange; // необязательные VTF-прицелы
    private static int texBlueSize, texOrangeSize;
    private static boolean texTried;

    private static KeyBinding toggleKey, resetKey;

    @Override
    public void onInitializeClient() {
        PortalConfig.load();
        portalMode = PortalConfig.enabled();

        KeyBinding.Category cat = Compat.category();
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.portalcraft.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, cat));
        resetKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.portalcraft.reset", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R, cat));

        Thread loader = new Thread(PortalClient::loadGame, "PortalCraft-loader");
        loader.setDaemon(true);
        loader.start();

        // --- сеть ---
        ClientPlayNetworking.registerGlobalReceiver(PortalNet.State.ID,
                (payload, ctx) -> {
                    blueOpen = payload.blue();
                    orangeOpen = payload.orange();
                });
        ClientPlayNetworking.registerGlobalReceiver(PortalNet.Sound.ID,
                (payload, ctx) -> PortalSounds.play(payload.id(), volume()));

        // --- ввод ---
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (uiCooldown > 0) uiCooldown--;
            while (toggleKey.wasPressed()) toggle(client);
            while (resetKey.wasPressed()) {
                if (portalMode && client.player != null) ClientPlayNetworking.send(new PortalNet.Reset());
            }
        });

        // ЛКМ: синий портал, ломать блоки/бить в режиме Portal нельзя
        ClientPreAttackCallback.EVENT.register((client, player, clicks) -> {
            if (!portalMode) return false;
            shoot(false);
            return true;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
                portalMode ? ActionResult.FAIL : ActionResult.PASS);
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                portalMode ? ActionResult.FAIL : ActionResult.PASS);

        // ПКМ: оранжевый портал
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (!portalMode) return ActionResult.PASS;
            if (hand == Hand.MAIN_HAND && world.isClient()) shoot(true);
            return ActionResult.FAIL;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!portalMode) return ActionResult.PASS;
            if (hand == Hand.MAIN_HAND && world.isClient()) shoot(true);
            return ActionResult.FAIL;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!portalMode) return ActionResult.PASS;
            if (hand == Hand.MAIN_HAND && world.isClient()) shoot(true);
            return ActionResult.FAIL;
        });

        // --- HUD: прячем ванильный, рисуем свой пока включён режим Portal ---
        Identifier[] hidden = {
                VanillaHudElements.HEALTH_BAR, VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR,
                VanillaHudElements.MOUNT_HEALTH, VanillaHudElements.INFO_BAR,
                VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.CROSSHAIR};
        for (Identifier id : hidden) {
            HudElementRegistry.replaceElement(id, original -> (ctx, tick) -> {
                if (!portalMode) original.render(ctx, tick);
            });
        }
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR,
                Identifier.of("portalcraft", "hud"), (ctx, tick) -> {
                    if (portalMode) drawHud(ctx);
                });
    }

    // ---------- загрузка игры ----------

    private static void loadGame() {
        try {
            Path dir = PortalFinder.find(PortalConfig.get("portal_dir", ""));
            if (dir == null) {
                missing = "Steam/steamapps/common/Portal";
                PortalServer.LOG.warn("Portal не найден. Укажите portal_dir в config/portalcraft.properties");
                return;
            }
            PortalAssets.init(dir);
            PortalServer.LOG.info("Portal: {} (архивов VPK: {})", dir, PortalAssets.count());
            PortalAssets.dump(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                    .resolve("portalcraft-assets.txt"));
        } catch (Exception e) {
            missing = e.toString();
            PortalServer.LOG.error("Не удалось прочитать Portal", e);
        }
    }

    /** Необязательные прицелы из VTF: пути задаются в config (crosshair_blue / crosshair_orange). */
    private static void loadCrosshairTextures() {
        if (texTried || !PortalAssets.ready()) return;
        texTried = true;
        Vtf b = PortalAssets.vtf(PortalConfig.get("crosshair_blue", ""));
        Vtf o = PortalAssets.vtf(PortalConfig.get("crosshair_orange", ""));
        if (b != null) { texBlue = Compat.registerTexture("cross_blue", b.width, b.height, b.argb); texBlueSize = b.width; }
        if (o != null) { texOrange = Compat.registerTexture("cross_orange", o.width, o.height, o.argb); texOrangeSize = o.width; }
    }

    // ---------- действия ----------

    private static float volume() {
        MinecraftClient c = MinecraftClient.getInstance();
        return c.options.getSoundVolumeOption(SoundCategory.MASTER).getValue().floatValue();
    }

    private static void shoot(boolean orange) {
        if (uiCooldown > 0) return;
        uiCooldown = 4;
        if (MinecraftClient.getInstance().player == null) return;
        ClientPlayNetworking.send(new PortalNet.Fire(orange));
    }

    private static void toggle(MinecraftClient client) {
        portalMode = !portalMode;
        PortalConfig.set("enabled", Boolean.toString(portalMode));
        if (client.player == null) return;
        if (portalMode && missing != null) {
            client.player.sendMessage(Text.translatable("portalcraft.missing", missing), false);
        }
        client.player.sendMessage(Text.translatable(portalMode ? "portalcraft.mode.portal" : "portalcraft.mode.mc"), true);
        PortalSounds.play(portalMode ? "hud_on" : "hud_off", volume());
    }

    // ---------- HUD ----------

    private static void drawHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        int w = ctx.getScaledWindowWidth(), h = ctx.getScaledWindowHeight();
        drawCrosshair(ctx, w / 2, h / 2);

        // Здоровье как в HL2: число на шкале 100 и подпись
        int hp = Math.round(mc.player.getHealth() / mc.player.getMaxHealth() * 100f);
        int color = hp <= 25 ? 0xFFFF3030 : HUD_YELLOW;
        ctx.drawText(mc.textRenderer, Text.translatable("portalcraft.hud.health"), 12, h - 22, color, false);
        ctx.drawText(mc.textRenderer, String.valueOf(hp), 12 + mc.textRenderer.getWidth(
                Text.translatable("portalcraft.hud.health")) + 8, h - 22, color, false);
    }

    /** Прицел Portal: левая дуга синяя, правая оранжевая; полная яркость, когда портал открыт. */
    private static void drawCrosshair(DrawContext ctx, int cx, int cy) {
        loadCrosshairTextures();
        if (texBlue != null || texOrange != null) {
            if (texBlue != null) Compat.drawTexture(ctx, texBlue, cx - 16, cy - 16, 32, 32, texBlueSize, texBlueSize);
            if (texOrange != null) Compat.drawTexture(ctx, texOrange, cx - 16, cy - 16, 32, 32, texOrangeSize, texOrangeSize);
            return;
        }
        int blue = blueOpen ? 0xFF2D7CFF : 0x802D7CFF;
        int orange = orangeOpen ? 0xFFFF8A1E : 0x80FF8A1E;
        int r = 10;
        for (int i = 0; i < 16; i++) {
            double a = Math.toRadians(110 + i * (140.0 / 15.0));
            int x = (int) Math.round(Math.cos(a) * r), y = (int) Math.round(-Math.sin(a) * r);
            ctx.fill(cx + x - 1, cy + y - 1, cx + x + 1, cy + y + 1, blue);
            ctx.fill(cx - x - 1, cy + y - 1, cx - x + 1, cy + y + 1, orange);
        }
        ctx.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
    }
}
