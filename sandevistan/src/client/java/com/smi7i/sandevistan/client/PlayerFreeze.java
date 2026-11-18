package com.smi7i.sandevistan.client;

import com.smi7i.sandevistan.FreezePayload;
import com.smi7i.sandevistan.Sandevistan;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;

/**
 * «Замирание» игрока без Сандевистана в чужой зоне (команда приходит с сервера, FreezePayload).
 *  - Пока заморожен: управление отключено, мир на экране стоит, а всё, что присылает сервер, копится в очереди
 *    (кроме проверок связи — иначе выкинет с сервера).
 *  - Когда отпустило: накопленное проигрывается в REPLAY_SPEED раз быстрее — игрок видит, что произошло.
 */
public final class PlayerFreeze {
    /** Во сколько раз быстрее показывается то, что произошло, пока игрок стоял. */
    public static final double REPLAY_SPEED = 10.0;

    private record Entry(Packet<?> packet, long nanos) {}

    private static final ConcurrentLinkedQueue<Entry> QUEUE = new ConcurrentLinkedQueue<>();
    private static volatile boolean frozen;
    private static volatile boolean replaying;
    private static long freezeStart, replayStart;

    private PlayerFreeze() {}

    public static void init() {
        // Команду «замри/отомри» ловим раньше, прямо при получении (ConnectionMixin); тут — чтобы Fabric знал пакет.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(FreezePayload.TYPE,
                (payload, context) -> { });
        ClientTickEvents.START_CLIENT_TICK.register(PlayerFreeze::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            frozen = false;
            replaying = false;
            QUEUE.clear();
        });
    }

    /** Заморожен ли игрок (управление отключено). */
    public static boolean frozen() {
        return frozen;
    }

    /** Сервер сказал «замри» / «отомри». Вызывается из ConnectionMixin сразу при получении. */
    public static void onFreezePacket(boolean value) {
        if (value && !frozen) {
            frozen = true;
            freezeStart = System.nanoTime();
            Minecraft.getInstance().execute(() -> {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null) mc.level.tickRateManager().setFrozen(true); // мир на экране стоит
            });
        } else if (!value && frozen) {
            frozen = false;
            replaying = true;
            replayStart = 0;
        }
    }

    /**
     * Нужно ли отложить пакет (вызывается в сетевом потоке). Пока заморожен или идёт повтор — копим всё,
     * кроме проверок связи и разрыва соединения.
     */
    public static boolean shouldBuffer(Packet<?> packet, Object listener) {
        if (!(listener instanceof ClientPacketListener)) return false;
        if (packet instanceof ClientboundCustomPayloadPacket custom && custom.payload() instanceof FreezePayload freeze) {
            onFreezePacket(freeze.frozen());
            return false;
        }
        if (!frozen && !replaying) return false;
        if (packet instanceof ClientboundKeepAlivePacket || packet instanceof ClientboundPingPacket
                || packet instanceof ClientboundDisconnectPacket) return false;
        QUEUE.add(new Entry(packet, System.nanoTime()));
        return true;
    }

    /** Повтор накопленного ×10 (главный поток). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void tick(Minecraft mc) {
        if (!replaying || frozen) return;
        ClientPacketListener listener = mc.getConnection();
        if (listener == null) {
            QUEUE.clear();
            replaying = false;
            return;
        }
        long now = System.nanoTime();
        if (replayStart == 0) {
            replayStart = now;
            if (mc.level != null) mc.level.tickRateManager().setFrozen(false);
        }
        // Пакет, пришедший через T после заморозки, показываем через T / 10 после начала повтора.
        double replayedNanos = (now - replayStart) * REPLAY_SPEED;
        Entry e;
        while ((e = QUEUE.peek()) != null && e.nanos() - freezeStart <= replayedNanos) {
            QUEUE.poll();
            try {
                ((Packet) e.packet()).handle(listener);
            } catch (Exception ex) {
                Sandevistan.LOGGER.warn("Sandevistan: не удалось повторить пакет {}", e.packet(), ex);
            }
        }
        if (QUEUE.isEmpty()) replaying = false;
    }

    /** Идёт ли повтор накопленного. */
    public static boolean replaying() {
        return replaying;
    }
}
