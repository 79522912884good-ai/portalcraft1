package gg.portalcraft;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Серверная часть: портальная пушка. Порталы — это данные игрока (синий и оранжевый),
 * нарисованные частицами; вход в один выводит из другого с сохранением скорости.
 */
public final class PortalServer implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("portalcraft");

    static final double RANGE = 128.0;
    static final int FIRE_COOLDOWN = 6;     // тиков между выстрелами
    static final int TELEPORT_COOLDOWN = 12;
    static final int BLUE = 0x2D7CFF, ORANGE = 0xFF8A1E;

    /** Один портал: на грани блока. */
    record Portal(RegistryKey<World> dim, BlockPos block, Direction face, Vec3d center, Vec3d up, Vec3d right) {
        Vec3d normal() { return Vec3d.of(face.getVector()); }
        boolean horizontalNormal() { return face.getAxis().isHorizontal(); }
    }

    static final class Pair {
        final Portal[] p = new Portal[2];
        int fireCd, tpCd;
        Vec3d last;
        double lastSpeed;
    }

    private static final Map<UUID, Pair> STATE = new HashMap<>();

    @Override
    public void onInitialize() {
        PortalNet.register();

        ServerPlayNetworking.registerGlobalReceiver(PortalNet.Fire.ID,
                (payload, ctx) -> fire(ctx.player(), payload.orange()));
        ServerPlayNetworking.registerGlobalReceiver(PortalNet.Reset.ID, (payload, ctx) -> reset(ctx.player(), true));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> STATE.remove(handler.getPlayer().getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity pl : server.getPlayerManager().getPlayerList()) tick(pl);
        });
    }

    // ---------- выстрел ----------

    private static void fire(ServerPlayerEntity player, boolean orange) {
        Pair st = STATE.computeIfAbsent(player.getUuid(), k -> new Pair());
        if (st.fireCd > 0) return;
        st.fireCd = FIRE_COOLDOWN;

        ServerWorld world = Compat.worldOf(player);
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(RANGE));
        BlockHitResult hit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        ServerPlayNetworking.send(player, new PortalNet.Sound(orange ? "orange" : "blue"));
        if (hit.getType() != HitResult.Type.BLOCK || !canHold(world, hit.getBlockPos(), hit.getSide())) {
            ServerPlayNetworking.send(player, new PortalNet.Sound("invalid"));
            return;
        }

        BlockPos pos = hit.getBlockPos();
        Direction face = hit.getSide();
        Vec3d n = Vec3d.of(face.getVector());
        Vec3d center = Vec3d.ofCenter(pos).add(n.multiply(0.5));
        Vec3d up;
        if (face.getAxis().isHorizontal()) {
            up = new Vec3d(0, 1, 0);
            center = center.add(0, 0.45, 0); // нижний край овала на уровне низа блока
        } else {
            up = Vec3d.of(player.getHorizontalFacing().getVector());
        }
        Vec3d right = n.crossProduct(up);
        Portal portal = new Portal(world.getRegistryKey(), pos, face, center, up, right);

        int me = orange ? 1 : 0, other = 1 - me;
        Portal o = st.p[other];
        if (o != null && o.dim().equals(portal.dim()) && o.block().equals(pos) && o.face() == face) {
            ServerPlayNetworking.send(player, new PortalNet.Sound("invalid"));
            return;
        }
        st.p[me] = portal;
        sendState(player, st);
    }

    /** Портал можно поставить на целый непробиваемый-не-бедрок блок, если перед гранью свободно. */
    private static boolean canHold(ServerWorld world, BlockPos pos, Direction face) {
        BlockState s = world.getBlockState(pos);
        if (s.getHardness(world, pos) < 0) return false; // бедрок и т.п.
        if (!s.isSideSolidFullSquare(world, pos, face)) return false;
        BlockPos front = pos.offset(face);
        return world.getBlockState(front).getCollisionShape(world, front).isEmpty();
    }

    private static void reset(ServerPlayerEntity player, boolean sound) {
        Pair st = STATE.get(player.getUuid());
        if (st == null || (st.p[0] == null && st.p[1] == null)) return;
        st.p[0] = st.p[1] = null;
        sendState(player, st);
        if (sound) ServerPlayNetworking.send(player, new PortalNet.Sound("fizzle"));
    }

    private static void sendState(ServerPlayerEntity player, Pair st) {
        ServerPlayNetworking.send(player, new PortalNet.State(st.p[0] != null, st.p[1] != null));
    }

    // ---------- тик: частицы и телепорт ----------

    private static void tick(ServerPlayerEntity player) {
        Pair st = STATE.get(player.getUuid());
        if (st == null) return;
        if (st.fireCd > 0) st.fireCd--;
        if (st.tpCd > 0) st.tpCd--;

        ServerWorld world = Compat.worldOf(player);
        Vec3d pos = Compat.posOf(player);
        double speed = 0;
        if (st.last != null) {
            Vec3d d = pos.subtract(st.last);
            speed = Math.max(d.length(), st.lastSpeed * 0.6);
        }

        // Если блок под порталом исчез, портал закрывается.
        for (int i = 0; i < 2; i++) {
            Portal p = st.p[i];
            if (p != null && p.dim().equals(world.getRegistryKey()) && !canHold(world, p.block(), p.face())) {
                st.p[i] = null;
                sendState(player, st);
                ServerPlayNetworking.send(player, new PortalNet.Sound("fizzle"));
            }
        }

        if (player.age % 3 == 0) {
            drawRing(world, st.p[0], BLUE);
            drawRing(world, st.p[1], ORANGE);
        }

        if (st.tpCd == 0 && st.p[0] != null && st.p[1] != null && st.last != null) {
            Vec3d delta = pos.subtract(st.last);
            for (int i = 0; i < 2; i++) {
                Portal in = st.p[i], out = st.p[1 - i];
                if (!in.dim().equals(world.getRegistryKey()) || !out.dim().equals(world.getRegistryKey())) continue;
                if (touching(player, pos, delta, in)) {
                    pass(player, world, st, in, out, speed);
                    pos = Compat.posOf(player);
                    break;
                }
            }
        }
        st.last = Compat.posOf(player);
        st.lastSpeed = speed;
    }

    private static boolean touching(ServerPlayerEntity player, Vec3d feet, Vec3d delta, Portal p) {
        Vec3d n = p.normal();
        Vec3d ref; // точка тела, которая упирается в портал
        if (p.face() == Direction.UP) ref = feet;
        else if (p.face() == Direction.DOWN) ref = feet.add(0, 1.8, 0);
        else ref = feet.add(0, 0.9, 0);
        Vec3d d = ref.subtract(p.center());
        double dn = d.dotProduct(n);
        if (dn > 0.45 || dn < -0.5) return false;
        double a = d.dotProduct(p.right()) / 0.55;
        double b = d.dotProduct(p.up()) / 1.0;
        if (a * a + b * b > 1.0) return false;
        return delta.dotProduct(n) < -0.003; // двигаемся в сторону портала
    }

    private static void pass(ServerPlayerEntity player, ServerWorld world, Pair st, Portal in, Portal out, double speed) {
        Vec3d n2 = out.normal();
        Vec3d dest;
        if (out.face() == Direction.UP) dest = out.center().add(0, 0.1, 0);
        else if (out.face() == Direction.DOWN) dest = out.center().add(0, -1.9, 0);
        else dest = new Vec3d(out.center().x + n2.x * 0.4, out.center().y - 0.95, out.center().z + n2.z * 0.4);

        float yaw = player.getYaw(), pitch = player.getPitch();
        if (in.horizontalNormal() && out.horizontalNormal()) {
            yaw = yaw - yawOf(in.normal().multiply(-1)) + yawOf(n2);
        } else if (out.horizontalNormal()) {
            yaw = yawOf(n2);
        }

        double v = out.horizontalNormal() ? Math.max(speed, 0.3) : speed;
        v = Math.min(v, 3.0);
        Compat.teleport(player, world, dest, yaw, pitch);
        player.setVelocity(n2.multiply(v));
        player.networkHandler.sendPacket(new EntityVelocityUpdateS2CPacket(player));
        player.fallDistance = 0;
        st.tpCd = TELEPORT_COOLDOWN;
        ServerPlayNetworking.send(player, new PortalNet.Sound("enter"));
        ServerPlayNetworking.send(player, new PortalNet.Sound("exit"));
    }

    private static float yawOf(Vec3d dir) {
        return (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
    }

    private static void drawRing(ServerWorld world, Portal p, int color) {
        if (p == null || !p.dim().equals(world.getRegistryKey())) return;
        DustParticleEffect fx = new DustParticleEffect(color, 0.8f);
        Vec3d n = p.normal().multiply(0.03);
        for (int i = 0; i < 28; i++) {
            double a = Math.PI * 2 * i / 28.0;
            Vec3d o = p.right().multiply(Math.cos(a) * 0.5).add(p.up().multiply(Math.sin(a) * 0.95));
            Vec3d at = p.center().add(n).add(o);
            world.spawnParticles(fx, at.x, at.y, at.z, 1, 0, 0, 0, 0);
        }
    }
}
