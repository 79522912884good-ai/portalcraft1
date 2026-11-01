package gg.portalcraft;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import java.util.Set;

/**
 * ВСЕ вызовы Minecraft API, которые чаще всего меняются между версиями, собраны здесь.
 * Если сборка на 1.21.11 ругается на сигнатуру, править нужно только этот файл.
 */
public final class Compat {
    private Compat() {}

    public static ServerWorld worldOf(ServerPlayerEntity p) { return p.getEntityWorld(); }

    public static Vec3d posOf(ServerPlayerEntity p) { return p.getEntityPos(); }

    public static KeyBinding.Category category() {
        return KeyBinding.Category.create(Identifier.of("portalcraft", "main"));
    }

    public static void teleport(ServerPlayerEntity p, ServerWorld w, Vec3d pos, float yaw, float pitch) {
        p.teleport(w, pos.x, pos.y, pos.z, Set.of(), yaw, pitch, true);
    }

    /** Регистрирует ARGB-картинку как текстуру и возвращает её id. */
    public static Identifier registerTexture(String name, int w, int h, int[] argb) {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) img.setColorArgb(x, y, argb[y * w + x]);
        Identifier id = Identifier.of("portalcraft", name);
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> name, img));
        return id;
    }

    public static void drawTexture(DrawContext ctx, Identifier tex, int x, int y, int w, int h, int texW, int texH) {
        RenderPipeline pipe = RenderPipelines.GUI_TEXTURED;
        ctx.drawTexture(pipe, tex, x, y, 0f, 0f, w, h, texW, texH, texW, texH);
    }
}
