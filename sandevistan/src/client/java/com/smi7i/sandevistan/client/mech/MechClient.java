package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.platform.InputConstants;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.mech.MechManager;
import com.smi7i.sandevistan.mech.MechSuitEntity;
import com.smi7i.sandevistan.mech.SummonMechPayload;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

/** Клиент костюма-меха: клавиша-способность, отрисовщик, слой на игроке, подсказка «[ПКМ]», размеры игроков. */
public final class MechClient {
    public static KeyMapping SUMMON;
    /** Способность «шприц»: укол жидкости из баллонов костюма, +30 секунд жизни (5 уколов). */
    public static KeyMapping INJECT;
    /** Способность «атака» костюма: комбо из трёх ударов. */
    public static KeyMapping ATTACK;
    /** Снять костюм (зажать EJECT_HOLD тиков). Пилот при этом погибает. */
    public static KeyMapping EJECT;
    public static final int EJECT_HOLD = 30;
    /** Сколько тиков уже зажата клавиша снятия. */
    static int ejectHeld;
    /** Способность «вихрь»: зона, все вокруг кружатся, костюм парит, удар вниз. */
    public static KeyMapping VORTEX;
    /** «Последний бой» — когда шкала уколов полная. */
    public static KeyMapping SHOWDOWN;
    /** Высота земли в начале «вихря» (для взлёта и спуска). */
    private static double vortexGroundY = Double.NaN;

    /** Высота пилота над землёй в «вихре» (блоки) по времени (тики): взлёт, парение с покачиванием, плавный спуск. */
    public static double vortexHeight(float age) {
        float up = smooth((age - 12) / 16.0F);                                   // 0.6 → 1.4 с — взлёт
        float down = smooth((age - com.smi7i.sandevistan.mech.MechVortex.SLAM) / 26.0F); // после удара — плавно вниз
        double hover = 3.0 + 0.18 * Math.sin(age * 2 * Math.PI / 24.0);
        return hover * up * (1 - down);
    }

    private static float smooth(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** Свой пилот в «вихре»: скорость по вертикали — к нужной высоте, по горизонтали — стоит. */
    private static void vortexTick(Minecraft mc) {
        var player = mc.player;
        if (player == null || mc.level == null) return;
        long start = player.getAttachedOrElse(ModAttachments.MECH_SKILL_START, 0L);
        if (start <= 0 || !com.smi7i.sandevistan.mech.MechVortex.isActive(player)) {
            vortexGroundY = Double.NaN;
            return;
        }
        if (Double.isNaN(vortexGroundY)) vortexGroundY = player.getY();
        float age = mc.level.getGameTime() - start;
        double target = vortexGroundY + vortexHeight(age + 1);
        player.setDeltaMovement(0, (target - player.getY()) * 0.5, 0);
        player.fallDistance = 0;
    }

    // ===== «Метеор» =====
    /** Зажал — костюм прыгает и исчезает, летишь над землёй; отпустил — удар об землю с зоной. */
    public static KeyMapping METEOR;
    /** Скорость полёта невидимым (блоков за тик): ~8 блоков в секунду — быстро, но можно насладиться. */
    public static final double METEOR_FLY_SPEED = 0.42;
    private static boolean meteorHeld;
    /** Своя скорость полёта (не берём у игрока — её съедает трение воздуха). */
    private static double meteorVx, meteorVz;
    private static double meteorGroundY = Double.NaN;

    /** Высота земли под точкой: самый высокий блок в квадрате 3×3 (чтобы не цеплять деревья и уступы). */
    public static double groundUnder(net.minecraft.world.level.Level level, double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
        int top = Integer.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                top = Math.max(top, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx + dx, bz + dz));
            }
        }
        return top;
    }

    /** Свой пилот в «метеоре»: присед и прыжок, полёт на высоте HOVER над рельефом, зависание и резкое падение. */
    private static void meteorTick(Minecraft mc) {
        var player = mc.player;
        if (player == null || mc.level == null) return;
        // Клавиша: нажал — старт, отпустил — удар.
        boolean down = METEOR.isDown() && mc.gui.screen() == null;
        while (METEOR.consumeClick()) { }
        if (down && !meteorHeld && ModAttachments.hasMech(player) && MechLifeHud.meteorCd <= 0 && !localAttacking()
                && !com.smi7i.sandevistan.mech.MechMeteor.isActive(player) && !com.smi7i.sandevistan.mech.MechVortex.isActive(player)) {
            ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.MeteorPayload(0));
        }
        if (!down && meteorHeld && com.smi7i.sandevistan.mech.MechMeteor.isActive(player)) {
            ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.MeteorPayload(1));
        }
        meteorHeld = down;
        long s = com.smi7i.sandevistan.mech.MechMeteor.startTime(player);
        if (s <= 0) {
            if (!Double.isNaN(meteorGroundY)) player.setNoGravity(false);
            meteorVx = meteorVz = 0;
            meteorGroundY = Double.NaN;
            return;
        }
        if (Double.isNaN(meteorGroundY)) meteorGroundY = player.getY();
        player.setNoGravity(true);
        player.fallDistance = 0;
        long now = mc.level.getGameTime();
        long age = now - s;
        long d = com.smi7i.sandevistan.mech.MechMeteor.dropTime(player);
        int launch = com.smi7i.sandevistan.mech.MechMeteor.LAUNCH;
        double ground = groundUnder(mc.level, player.getX(), player.getZ());
        double hover = ground + com.smi7i.sandevistan.mech.MechMeteor.HOVER;
        if (d <= 0 && age < launch) {
            // Присел, потом рывком вверх.
            double vy = age < launch - 5 ? 0 : (hover - player.getY()) * 0.45;
            player.setDeltaMovement(0, vy, 0);
            return;
        }
        if (d <= 0) {
            // Невидим: летит по WASD (по направлению взгляда), высота повторяет рельеф.
            var in = player.input.getMoveVector();
            double vx = 0, vz = 0;
            if (in.lengthSquared() > 0.01F) {
                double yaw = Math.toRadians(player.getYRot());
                double fx = -Math.sin(yaw), fz = Math.cos(yaw);
                double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
                double len = Math.sqrt(in.lengthSquared());
                vx = (fx * in.y - rx * in.x) / len * METEOR_FLY_SPEED;
                vz = (fz * in.y - rz * in.x) / len * METEOR_FLY_SPEED;
            }
            // Плавный разгон и торможение — полёт «скольжением», а не рывками.
            meteorVx += (vx - meteorVx) * 0.18;
            meteorVz += (vz - meteorVz) * 0.18;
            player.setDeltaMovement(meteorVx, (hover - player.getY()) * 0.25, meteorVz);
            return;
        }
        long since = now - d;
        int aim = com.smi7i.sandevistan.mech.MechMeteor.AIM;
        if (since < aim - 3) {
            player.setDeltaMovement(0, (hover - player.getY()) * 0.5, 0); // висит над зоной
        } else if (since < aim + 2) {
            // Резко вниз: за 3 тика до земли.
            double left = Math.max(1, aim - since);
            player.setDeltaMovement(0, Math.min(-0.5, (ground - player.getY()) / left), 0);
        } else {
            player.setNoGravity(false);
            player.setDeltaMovement(0, Math.min(0, player.getDeltaMovement().y), 0);
        }
    }

    /** Рывок на DASH_DISTANCE блоков (по умолчанию Q — в костюме Q не выбрасывает предмет). */
    public static KeyMapping DASH;

    /**
     * Рывок-телепорт (в начале тика, до обработки клавиш игрой): по нажатию просим сервер перенести костюм
     * на DASH_DISTANCE блоков туда, куда жмёшь (WASD), а если стоишь — куда смотришь.
     */
    private static void dashTick(Minecraft mc) {
        var player = mc.player;
        if (player == null || mc.level == null) return;
        boolean inMech = ModAttachments.hasMech(player);
        // Q в костюме — рывок, а не «выбросить предмет»: забираем нажатия до того, как игра их обработает.
        boolean pressed = false;
        while (DASH.consumeClick()) pressed = true;
        if (inMech) {
            boolean sameKey = mc.options.keyDrop.same(DASH);
            while (mc.options.keyDrop.consumeClick()) if (sameKey) pressed = true;
        }
        if (pressed && inMech && mc.gui.screen() == null && MechLifeHud.dashCharges > 0
                && !com.smi7i.sandevistan.mech.MechVortex.isActive(player)
                && !com.smi7i.sandevistan.mech.MechMeteor.isActive(player)
                && !com.smi7i.sandevistan.mech.MechSingularity.isActive(player)
                && !localAttacking() && !MechManager.isEquipping(player) && player.isAlive()) {
            // Направление — куда жмёшь (WASD), а если стоишь — куда смотришь.
            var in = player.input.getMoveVector();
            float yaw = player.getYRot();
            if (in.lengthSquared() > 0.01F) {
                yaw -= (float) Math.toDegrees(Math.atan2(in.x, in.y));
            }
            // Телепорт делает сервер (он же проверяет, чтобы не попасть в блоки) — клиент только просит.
            // Заряд тратится сразу (сервер подтвердит своим состоянием).
            if (MechLifeHud.dashCharges >= MechManager.DASH_CHARGES) MechLifeHud.dashCd = MechManager.DASH_COOLDOWN;
            MechLifeHud.dashCharges--;
            ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.DashPayload(yaw));
        }
    }

    /** Последнее известное «в костюме» по игрокам — чтобы обновить их размеры (хитбокс, высота глаз). */
    private static final Map<Integer, Boolean> LAST = new HashMap<>();

    private MechClient() {}

    public static void init(KeyMapping.Category category) {
        SUMMON = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
        INJECT = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_inject", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, category));
        ATTACK = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_attack", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        EJECT = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_eject", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, category));
        SHOWDOWN = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_showdown", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, category));
        VORTEX = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_vortex", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        DASH = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_dash", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Q, category));
        METEOR = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.sandevistan.mech_meteor", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Y, category));
        ClientTickEvents.START_CLIENT_TICK.register(MechClient::meteorTick);
        MechSingularityClient.init(category);
        SkyShatterClient.init();
        MechPushClient.init(category);
        // Кабина пилота (вид от первого лица) — под остальным интерфейсом.
        HudElementRegistry.attachElementBefore(VanillaHudElements.HOTBAR,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_cockpit"), (g, delta) -> MechCockpitHud.render(g));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_push"), (g, delta) -> MechPushClient.renderHud(g));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "mech_singularity"),
                (g, delta) -> MechSingularityClient.renderHud(g));
        ClientTickEvents.START_CLIENT_TICK.register(MechClient::dashTick);
        ClientTickEvents.START_CLIENT_TICK.register(MechClient::vortexTick);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_inject_flash"), (g, delta) -> MechLifeHud.renderInjectFlash(g));
        EntityRendererRegistry.register(MechManager.MECH_SUIT, MechSuitRenderer::new);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_abilities"), (g, delta) -> MechLifeHud.renderAbilities(g));
        MechLifeHud.init();
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_life"), (g, delta) -> MechLifeHud.render(g));
        MechWornLayer.register();
        MechMotion.init();
        MechFx.init();
        MechWreck.init(); // отвалившиеся куски и конечности костюма
        MechCutscene.init();
        MechShowdownClient.init();
        // «Последний бой» — весь обычный интерфейс скрыт; в костюме вместо сердечек — своя шкала здоровья сверху.
        for (Identifier id : new Identifier[]{VanillaHudElements.HOTBAR, VanillaHudElements.CROSSHAIR, VanillaHudElements.HEALTH_BAR,
                VanillaHudElements.ARMOR_BAR, VanillaHudElements.FOOD_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.MOUNT_HEALTH,
                VanillaHudElements.INFO_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.HELD_ITEM_TOOLTIP,
                VanillaHudElements.MOB_EFFECTS, VanillaHudElements.OVERLAY_MESSAGE}) {
            boolean hearts = id.equals(VanillaHudElements.HEALTH_BAR);
            HudElementRegistry.replaceElement(id, original -> (g, delta) -> {
                if (MechShowdownClient.hideHud()) return;
                if (hearts && localInMech()) return; // 100 сердечек не помещаются — у костюма своя шкала
                original.extractRenderState(g, delta);
            });
        }
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_health"), (g, delta) -> MechLifeHud.renderHealth(g));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_meteor_hud"), (g, delta) -> MechLifeHud.renderMeteorHud(g));
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "mech_showdown"),
                (g, delta) -> MechShowdownClient.render(g, delta.getGameTimeDeltaPartialTick(true)));
        // Кат-сцена посадки — поверх всего интерфейса.
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("sandevistan", "mech_cutscene"),
                (g, delta) -> MechCutscene.render(g, delta.getGameTimeDeltaPartialTick(true)));
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("sandevistan", "mech_hint"), (g, delta) -> hint(g));
        ClientTickEvents.END_CLIENT_TICK.register(MechClient::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            LAST.clear();
            return;
        }
        while (SUMMON.consumeClick()) {
            if (ModAttachments.hasMech(mc.player)) continue;
            if (!com.smi7i.sandevistan.ImplantSlot.isImplant(mc.player.getAttachedOrCreate(ModAttachments.IMPLANT))) continue; // без Сандевистана — ничего
            if (MechLifeHud.summonCd <= 0) ClientPlayNetworking.send(new SummonMechPayload());
        }
        while (ATTACK.consumeClick()) {
            if (ModAttachments.hasMech(mc.player) && !localAttacking() && MechLifeHud.attackCd <= 0) {
                ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.MechAttackPayload());
            }
        }
        while (SHOWDOWN.consumeClick()) {
            if (com.smi7i.sandevistan.mech.MechShowdown.available(mc.player) && !localAttacking()
                    && !com.smi7i.sandevistan.mech.MechShowdown.isActive(mc.player)) {
                ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.ShowdownPayload());
            }
        }
        while (VORTEX.consumeClick()) {
            if (ModAttachments.hasMech(mc.player) && !localAttacking() && MechLifeHud.skillCd <= 0
                    && !com.smi7i.sandevistan.mech.MechVortex.isActive(mc.player)) {
                ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.VortexPayload());
            }
        }
        while (INJECT.consumeClick()) {
            if (ModAttachments.hasMech(mc.player)) ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.InjectPayload());
        }
        // Снятие костюма: держать клавишу; отпустил раньше — отмена.
        while (EJECT.consumeClick()) { }
        if (ModAttachments.hasMech(mc.player) && EJECT.isDown() && mc.gui.screen() == null && mc.player.isAlive()) {
            if (++ejectHeld == EJECT_HOLD) ClientPlayNetworking.send(new com.smi7i.sandevistan.mech.EjectMechPayload());
        } else {
            ejectHeld = 0;
        }
        for (Player p : mc.level.players()) {
            boolean mech = ModAttachments.hasMech(p);
            Boolean was = LAST.put(p.getId(), mech);
            if (was == null || was != mech) p.refreshDimensions();
        }
    }

    /**
     * Вид от первого лица в костюме: своё тело (руки и тело костюма, тело пилота — без головы).
     * null — не нужно (вид от 3-го лица, не в костюме, душа при киберпсихозе).
     */
    public static net.minecraft.client.renderer.entity.state.EntityRenderState firstPersonBody(Minecraft mc, float partial) {
        if (mc.player == null || !ModAttachments.hasMech(mc.player) || !mc.options.getCameraType().isFirstPerson()) return null;
        if (mc.player.isInvisible()) return null; // «метеор»: исчез
        if (mc.getCameraEntity() != mc.player || com.smi7i.sandevistan.client.PsychosisSoul.active()) return null;
        var state = mc.getEntityRenderDispatcher().extractEntity(mc.player, partial);
        if (!(state instanceof net.minecraft.client.renderer.entity.state.AvatarRenderState avatar)) return null;
        avatar.setData(MechWornLayer.FIRST_PERSON, true);
        avatar.nameTag = null;
        avatar.shadowPieces.clear();
        avatar.shadowRadius = 0.0F;
        return avatar;
    }

    /** Наведён на пустой костюм — подсказка под прицелом. */
    private static void hint(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() != null || ModAttachments.hasMech(mc.player) || MechCutscene.active()) return;
        if (!(mc.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof MechSuitEntity)) return;
        int x = mc.getWindow().getGuiScaledWidth() / 2, y = mc.getWindow().getGuiScaledHeight() / 2 + 14;
        HoloUi.centeredText(g, mc.font, "[" + com.smi7i.sandevistan.client.HoloUi.key(mc.options.keyUse) + "] Влезть в костюм", x, y, 0x39FF6A, 1.0F);
    }

    /** Свой игрок сейчас атакует (костюм стоит на месте). */
    public static boolean localAttacking() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && MechManager.isAttacking(mc.player);
    }

    /** Свой игрок в костюме — для скрытия рук от первого лица. */
    public static boolean localInMech() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && ModAttachments.hasMech(mc.player);
    }
}
