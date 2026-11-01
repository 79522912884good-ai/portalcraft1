package gg.portalcraft;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/** Пакеты между клиентом и сервером. */
public final class PortalNet {
    private PortalNet() {}

    /** Клиент -> сервер: выстрел из портальной пушки (false = синий, true = оранжевый). */
    public record Fire(boolean orange) implements CustomPayload {
        public static final Id<Fire> ID = new Id<>(Identifier.of("portalcraft", "fire"));
        public static final PacketCodec<RegistryByteBuf, Fire> CODEC =
                PacketCodec.of((v, buf) -> buf.writeBoolean(v.orange()), buf -> new Fire(buf.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Клиент -> сервер: закрыть оба портала. */
    public record Reset() implements CustomPayload {
        public static final Id<Reset> ID = new Id<>(Identifier.of("portalcraft", "reset"));
        public static final PacketCodec<RegistryByteBuf, Reset> CODEC =
                PacketCodec.of((v, buf) -> {}, buf -> new Reset());
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Сервер -> клиент: какие порталы открыты (для прицела). */
    public record State(boolean blue, boolean orange) implements CustomPayload {
        public static final Id<State> ID = new Id<>(Identifier.of("portalcraft", "state"));
        public static final PacketCodec<RegistryByteBuf, State> CODEC = PacketCodec.of(
                (v, buf) -> { buf.writeBoolean(v.blue()); buf.writeBoolean(v.orange()); },
                buf -> new State(buf.readBoolean(), buf.readBoolean()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Сервер -> клиент: проиграть звук Portal по id (blue, orange, invalid, fizzle, enter, exit). */
    public record Sound(String id) implements CustomPayload {
        public static final Id<Sound> ID = new Id<>(Identifier.of("portalcraft", "sound"));
        public static final PacketCodec<RegistryByteBuf, Sound> CODEC =
                PacketCodec.of((v, buf) -> buf.writeString(v.id()), buf -> new Sound(buf.readString()));
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(Fire.ID, Fire.CODEC);
        PayloadTypeRegistry.playC2S().register(Reset.ID, Reset.CODEC);
        PayloadTypeRegistry.playS2C().register(State.ID, State.CODEC);
        PayloadTypeRegistry.playS2C().register(Sound.ID, Sound.CODEC);
    }
}
