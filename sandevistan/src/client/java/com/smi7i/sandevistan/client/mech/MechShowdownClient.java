package com.smi7i.sandevistan.client.mech;

import com.mojang.blaze3d.platform.NativeImage;
import com.smi7i.sandevistan.ModAttachments;
import com.smi7i.sandevistan.ModSounds;
import com.smi7i.sandevistan.client.AfterimageRenderState;
import com.smi7i.sandevistan.client.HoloUi;
import com.smi7i.sandevistan.client.SandevistanAfterimages;
import com.smi7i.sandevistan.client.SandevistanSounds;
import com.smi7i.sandevistan.mech.MechShowdown;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * «Последний бой» на клиенте.
 * 1) Кат-сцена (как в видео пользователя — финал Edgerunners), рисуется поверх всего экрана, тайминг под музыку:
 *    лицо пилота в кабине → темнота → две фигуры сидят на Луне и смотрят на Землю → глитч, тёмно-красная кабина,
 *    темнота, красная вспышка, трясущееся лицо, крик, цветные копии лица → темнота → мир.
 *    Персонажей рисует сама игра (как в окне инвентаря) — с настоящим скином и костюмом; вторая фигура — со скином
 *    пользователя (textures/entity/showdown_partner.png) и в шлеме.
 * 2) Полёт: пилот парит в 1 блоке над землёй, камера-«погоня» сверху сзади, рядом летят 2 клона со своими
 *    послеобразами, из-под всех летят осколки земли, по экрану — линии скорости и полосы кино.
 * 3) Со стороны (другие игроки): во время кат-сцены у пилота трясётся голова и он «глитчит» цветными копиями.
 */
public final class MechShowdownClient {
    // ===== Тайминг кат-сцены (тики), сумма = MechShowdown.CUTSCENE =====
    private static final int FACE_END = 50, BLACK1_END = 70, MOON_END = 170, DARK_RED_END = 180, BLACK2_END = 210,
            RED_END = 220, SHAKE_END = 250, SCREAM_END = 260, CLONES_END = 280, BLACK3_END = 290;
    /** Полосы «кино» сверху и снизу (доля высоты). */
    private static final float LETTERBOX = 0.09F;
    /** Полёт: высота над землёй (блоки), камера сзади/сверху, прибавка к FOV. */
    private static final double HOVER = 1.0, CAM_BACK = 5.0, CAM_UP = 2.5;
    /** Прибавка к углу обзора в ульте — небольшая (пользователь: «не настолько далёким»). */
    public static final float RUN_FOV_BOOST = 0.0F;
    /** Клоны: на сколько тиков отстают по пути и насколько сбоку. */
    private static final int CLONE_LAG = 5;
    private static final double CLONE_SIDE = 2.2;

    public static final Identifier PARTNER_SKIN = Identifier.fromNamespaceAndPath("sandevistan", "textures/entity/showdown_partner.png");

    private static long started;
    private static CameraType previousCamera;
    private static boolean cameraForced;
    private static final Map<Integer, ArrayDeque<Vec3>> TRAIL = new HashMap<>();
    private static final Random R = new Random();

    private MechShowdownClient() {}

    /** Призраки (только картинка): у каждого своя точка, к которой он идёт; бродят вокруг пилота куда попало. */
    private static final class Ghost {
        Vec3 pos, prev, target, vel = Vec3.ZERO;
        float yaw, prevYaw;
        int wait;
    }

    private static final Map<Integer, Ghost[]> GHOSTS = new HashMap<>();
    /** Скорость призраков — как у костюма в ульте (≈ бег), и как далеко от пилота они бродят. */
    private static final double GHOST_SPEED = 0.28, GHOST_MIN_R = 3.0, GHOST_MAX_R = 13.0;
    private static boolean diedInShowdown;
    private static long genericStarted;

    /** Игрок погиб в «Последнем бою» — показывать свой экран смерти вместо ванильного (GuiDeathScreenMixin). */
    public static boolean customDeathScreen() {
        return diedInShowdown;
    }

    private static void tickGhosts(Minecraft mc, Player p) {
        Ghost[] gs = GHOSTS.computeIfAbsent(p.getId(), k -> {
            Ghost[] arr = new Ghost[2];
            for (int i = 0; i < 2; i++) {
                arr[i] = new Ghost();
                Vec3 side = Vec3.directionFromRotation(0, p.getYRot() + (i == 0 ? 90 : -90)).scale(2.5);
                arr[i].pos = arr[i].prev = p.position().add(side);
                arr[i].yaw = arr[i].prevYaw = p.getYRot();
            }
            return arr;
        });
        Vec3 home = p.position();
        for (Ghost g : gs) {
            g.prev = g.pos;
            g.prevYaw = g.yaw;
            if (g.target == null || g.pos.distanceTo(g.target) < 1.2 || g.target.distanceTo(home) > GHOST_MAX_R + 4) {
                if (g.wait > 0) { // иногда постоят, «озираясь»
                    g.wait--;
                    g.vel = g.vel.scale(0.6);
                } else {
                    double ang = R.nextDouble() * Math.PI * 2, r = GHOST_MIN_R + R.nextDouble() * (GHOST_MAX_R - GHOST_MIN_R);
                    g.target = home.add(Math.cos(ang) * r, 0, Math.sin(ang) * r);
                    g.wait = R.nextInt(4) == 0 ? 8 + R.nextInt(20) : 0;
                }
            }
            if (g.target != null) {
                Vec3 d = new Vec3(g.target.x - g.pos.x, 0, g.target.z - g.pos.z);
                if (d.lengthSqr() > 1.0E-4) g.vel = g.vel.lerp(d.normalize().scale(GHOST_SPEED), 0.15);
            }
            double nx = g.pos.x + g.vel.x, nz = g.pos.z + g.vel.z;
            int gy = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(nx), Mth.floor(nz));
            double ny = Math.abs(gy - g.pos.y) > 3 ? gy : g.pos.y + (gy - g.pos.y) * 0.5;
            g.pos = new Vec3(nx, ny, nz);
            if (g.vel.lengthSqr() > 0.002) g.yaw = (float) Math.toDegrees(Math.atan2(-g.vel.x, g.vel.z));
        }
    }

    /** Клоны от сервера: id игрока → {предыдущие, текущие} данные (по клону x, y, z, поворот). */
    private static final Map<Integer, double[][]> CLONES = new HashMap<>();

    public static void init() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                com.smi7i.sandevistan.mech.ShowdownClonesPayload.TYPE, (payload, context) -> {
                    double[][] st = CLONES.get(payload.playerId());
                    double[] prev = st != null ? st[1] : payload.data();
                    CLONES.put(payload.playerId(), new double[][]{prev, payload.data()});
                });
        ClientTickEvents.START_CLIENT_TICK.register(MechShowdownClient::tick);
        // С шейдерпаком Iris сцена с Луной рисуется вместе с миром.
        net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!com.smi7i.sandevistan.client.IrisCompat.shadersActive() || !hasStage()) return;
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            submitStage(context.submitNodeCollector(), context.levelState().cameraRenderState, partial);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> {
            started = 0;
            TRAIL.clear();
            CLONES.clear();
        });
    }

    /** Время «Последнего боя» у игрока (тики с долей), -1 — нет. */
    public static float age(Player p, float partial) {
        if (p == null) return -1;
        long s = p.getAttachedOrElse(ModAttachments.MECH_SHOWDOWN_START, 0L);
        if (s <= 0) return -1;
        float a = p.level().getGameTime() - s + partial;
        return a < MechShowdown.END ? a : -1;
    }

    /** Идёт «Последний бой» у своего игрока — весь интерфейс скрыт (кроме самой кат-сцены и полос кино). */
    public static boolean hideHud() {
        Player p = Minecraft.getInstance().player;
        return age(p, 0) >= 0 || genericDeath(p, 0) >= 0;
    }

    /** Гибель в костюме вне «Последнего боя»: тики с начала кат-сцены, -1 — нет. */
    public static float genericDeath(Player p, float partial) {
        if (p == null) return -1;
        long s = p.getAttachedOrElse(ModAttachments.MECH_DEATH_START, 0L);
        return s > 0 ? p.level().getGameTime() - s + partial : -1;
    }

    /** Последняя гибель — в «Последнем бою» (для надписи на экране смерти). */
    public static boolean lastDeathShowdown = true;

    /** Свой игрок смотрит кат-сцену или погибает — управление и мышь заблокированы. */
    public static boolean locked() {
        float a = age(Minecraft.getInstance().player, 0);
        return (a >= 0 && a < MechShowdown.CUTSCENE) || a >= MechShowdown.DEATH_START || genericDeath(Minecraft.getInstance().player, 0) >= 0;
    }

    /** Летит («ульта» 15 с). */
    public static boolean running(Player p) {
        float a = age(p, 0);
        return a >= MechShowdown.CUTSCENE && a < MechShowdown.DEATH_START;
    }

    /** Гибель: секунды с начала анимации death, -1 — нет. */
    public static float deathTime(Player p, float partial) {
        if (p == null) return -1;
        float g = genericDeath(p, partial);
        if (g >= 0) return g / 20.0F; // любая гибель в костюме
        long s = p.getAttachedOrElse(ModAttachments.MECH_SHOWDOWN_START, 0L);
        if (s <= 0) return -1;
        // Без верхней границы: и после смерти (пока тело на экране) костюм лежит в последнем кадре гибели.
        float a = p.level().getGameTime() - s + partial;
        return a >= MechShowdown.DEATH_START ? (a - MechShowdown.DEATH_START) / 20.0F : -1;
    }

    /** Во время кат-сцены звуки мира почти не слышны (музыка мода — слышна). */
    public static float silence() {
        return locked() ? 1.0F : 0.0F;
    }

    // ---------- Тик ----------

    private static void tick(Minecraft mc) {
        var player = mc.player;
        if (player == null || mc.level == null) return;
        for (Player p : mc.level.players()) {
            if (running(p)) tickGhosts(mc, p); else GHOSTS.remove(p.getId());
        }
        // Погиб в «Последнем бою» — вместо обычного экрана смерти свой финал с кнопкой «ПЕРЕРОДИТЬСЯ».
        if (age(player, 0) >= MechShowdown.DEATH_START) {
            diedInShowdown = true;
            lastDeathShowdown = true;
        }
        long gs = player.getAttachedOrElse(ModAttachments.MECH_DEATH_START, 0L);
        if (gs > 0) {
            diedInShowdown = true; // свой экран смерти и при обычной гибели в костюме
            lastDeathShowdown = false;
            if (gs != genericStarted) {
                genericStarted = gs;
                SandevistanSounds.play(mc, ModSounds.MECH_HOLLOW, 0.8F);
                SandevistanSounds.play(mc, ModSounds.MECH_FLATLINE, 0.55F);
            }
        }
        if (diedInShowdown && mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen) {
            mc.gui.setScreen(new ShowdownDeathScreen());
            diedInShowdown = false;
        } else if (diedInShowdown && player.isAlive() && age(player, 0) < 0 && gs <= 0) {
            diedInShowdown = false;
            // Возродился — закрываем свой экран смерти (как ванильный: он закрывается, когда новый игрок уже в мире).
            if (mc.gui.screen() instanceof ShowdownDeathScreen) mc.gui.setScreen(null);
        }
        long s = player.getAttachedOrElse(ModAttachments.MECH_SHOWDOWN_START, 0L);
        float a = age(player, 0);
        if (s > 0 && s != started && a >= 0) {
            // Начало: всё стихает, играет музыка из видео.
            started = s;
            stage = null;
            mc.getSoundManager().stop();
            SandevistanSounds.play(mc, ModSounds.MECH_SHOWDOWN, 1.0F);
            MechFx.shoulderWaves(player, 5);
        }
        // Кат-сцена кончилась — звук из видео (музыка + эффекты) сменяется продолжением той же песни, до гибели.
        if (s > 0 && a >= MechShowdown.CUTSCENE && a < MechShowdown.CUTSCENE + 1) {
            SandevistanSounds.play(mc, ModSounds.MECH_SHOWDOWN_SONG, 1.0F);
        }
        // Начало гибели — гулкий звук поверх музыки (музыка играет до самой гибели).
        if (s > 0 && a >= MechShowdown.DEATH_START && a < MechShowdown.DEATH_START + 1) {
            SandevistanSounds.play(mc, ModSounds.MECH_HOLLOW, 0.8F);
            SandevistanSounds.play(mc, ModSounds.MECH_FLATLINE, 0.55F);
        }
        // От третьего лица — только кат-сцена и гибель (их снимает своя камера). В ульте камера — как у игрока.
        boolean cinematic = (a >= 0 && a < MechShowdown.CUTSCENE) || a >= MechShowdown.DEATH_START || gs > 0;
        if (cinematic && !cameraForced) {
            previousCamera = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            cameraForced = true;
        } else if (!cinematic && cameraForced) {
            if (previousCamera != null) mc.options.setCameraType(previousCamera);
            cameraForced = false;
        }
    }
    /** Пилот парит в HOVER блоках над землёй: вертикальная скорость — к нужной высоте, ходить можно как обычно. */
    private static void hover(Minecraft mc) {
        var p = mc.player;
        double ground = Double.NaN;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy < 8; dy++) {
            pos.set(p.getX(), p.getY() + 0.5 - dy, p.getZ());
            var st = mc.level.getBlockState(pos);
            var shape = st.getCollisionShape(mc.level, pos);
            if (!shape.isEmpty()) {
                ground = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                break;
            }
        }
        double target = Double.isNaN(ground) ? p.getY() - 0.4 : ground + HOVER + 0.08 * Math.sin(p.tickCount * 0.35);
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x, Mth.clamp((target - p.getY()) * 0.45, -0.7, 0.7), v.z);
        p.fallDistance = 0;
    }

    /** Где клон i (по данным сервера, плавно между тиками). null — данных ещё нет. */
    private static Vec3 clonePos(Player p, int i, float partial) {
        double[][] st = CLONES.get(p.getId());
        if (st == null || st[1].length < (i + 1) * 4) return null;
        double[] a = st[0].length >= (i + 1) * 4 ? st[0] : st[1], b = st[1];
        int o = i * 4;
        return new Vec3(Mth.lerp(partial, a[o], b[o]), Mth.lerp(partial, a[o + 1], b[o + 1]), Mth.lerp(partial, a[o + 2], b[o + 2]));
    }

    /** Куда смотрит клон i (по направлению полёта). */
    private static float cloneYaw(Player p, int i, float partial) {
        double[][] st = CLONES.get(p.getId());
        if (st == null || st[1].length < (i + 1) * 4) return p.getYRot();
        double[] a = st[0].length >= (i + 1) * 4 ? st[0] : st[1];
        return Mth.rotLerp(partial, (float) a[i * 4 + 3], (float) st[1][i * 4 + 3]);
    }

    // ---------- Кадр мира: клоны и «глитч» со стороны ----------

    public static void addToFrame(Minecraft mc, float partial, List<EntityRenderState> target, List<EntityRenderState> world) {
        if (mc.level == null) return;
        for (Player p : mc.level.players()) {
            float a = age(p, partial);
            if (a < 0) continue;
            if (a >= MechShowdown.CUTSCENE && a < MechShowdown.DEATH_START && p instanceof AbstractClientPlayer acp) {
                // Два «призрака» — только картинка: полупрозрачные цветные копии медленно кружат рядом.
                // Без урона, без следов и эффектов (одна копия состояния на кадр — почти не нагружают).
                Ghost[] gs = GHOSTS.get(p.getId());
                for (int i = 0; gs != null && i < gs.length; i++) {
                    Ghost gh = gs[i];
                    Vec3 gp = gh.prev.lerp(gh.pos, partial);
                    AfterimageRenderState g = SandevistanAfterimages.snapshot(mc, acp);
                    g.x = gp.x;
                    g.y = gp.y;
                    g.z = gp.z;
                    g.bodyRot = Mth.rotLerp(partial, gh.prevYaw, gh.yaw);
                    g.yRot = 0;
                    float hue = (((p.tickCount + partial) * 0.006F) + i * 0.5F) % 1.0F;
                    g.afterimageColor = ARGB.color(0.32F, Mth.hsvToRgb(hue, 0.85F, 1.0F));
                    target.add(g);
                }            } else if (p != mc.player && p instanceof AbstractClientPlayer acp && R.nextFloat() < 0.55F) {
                // Со стороны: пилот «глитчит» — рядом мелькают красные и голубые копии со сдвигом.
                int copies = 1 + R.nextInt(2);
                for (int i = 0; i < copies; i++) {
                    AfterimageRenderState g = SandevistanAfterimages.snapshot(mc, acp);
                    Vec3 pos = p.getPosition(partial);
                    g.x = pos.x + (R.nextDouble() - 0.5) * 0.4;
                    g.y = pos.y + (R.nextDouble() - 0.5) * 0.15;
                    g.z = pos.z + (R.nextDouble() - 0.5) * 0.4;
                    g.afterimageColor = ARGB.color(0.35F + R.nextFloat() * 0.35F, R.nextBoolean() ? 0xFF2040 : 0x30E0FF);
                    world.add(g);
                }
            }
        }
    }

    // ---------- Камера полёта ----------

    /** Камера-«погоня» в полёте (свой игрок): сверху и сзади, смотрит на костюм. {x, y, z, yaw, pitch} или null. */
    public static double[] runCamera(float partial) {
        Minecraft mc = Minecraft.getInstance();
        // Ульта — камера игрока (свободная); своя камера только в анимации гибели.
        if (mc.player == null || deathTime(mc.player, partial) < 0) return null;
        Vec3 p = mc.player.getPosition(partial);
        float yaw = mc.player.getViewYRot(partial);
        float death = deathTime(mc.player, partial);
        if (death >= 0) {
            double[] shot = deathShot(mc, p, death, partial);
            if (shot != null) return shot;
        }
        if (death >= 0) {
            // Гибель: камера медленно обходит сбоку и опускается — смотрим, как костюм падает.
            yaw += 25.0F + 40.0F * Math.min(1.0F, death / 3.5F);
        }
        Vec3 back = Vec3.directionFromRotation(0, yaw).scale(-CAM_BACK * (death >= 0 ? 0.85 : 1.0));
        Vec3 target = p.add(0, death >= 0 ? 0.9 : 1.4, 0);
        Vec3 cam = p.add(back).add(0, death >= 0 ? CAM_UP * 0.55 : CAM_UP, 0);
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(target, cam, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) cam = hit.getLocation().add(target.subtract(hit.getLocation()).normalize().scale(0.3));
        Vec3 d = target.subtract(cam);
        double cy = Math.toDegrees(Math.atan2(-d.x, d.z));
        double cp = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new double[]{cam.x, cam.y, cam.z, cy, cp};
    }

    /**
     * Гибель — три плана со склейками, как в кино:
     * 1) 0–1.5 с — крупно лицо пилота снизу спереди, камера медленно наезжает (отказ систем, огни мигают);
     * 2) 1.5–3.9 с — низкий общий план сбоку спереди, медленный облёт: колени одно за другим бьют о землю;
     * 3) 3.9 с и дальше — кран: камера уходит назад и вверх, костюм на коленях, огни погасли.
     */
    private static double[] deathShot(Minecraft mc, Vec3 p, float t, float partial) {
        double yawB = Math.toRadians(MechMotion.bodyYaw(mc.player, partial));
        Vec3 f = new Vec3(-Math.sin(yawB), 0, Math.cos(yawB)), side = new Vec3(-Math.cos(yawB), 0, -Math.sin(yawB));
        Vec3 cam, target;
        if (t < 1.5F) {
            float k = t / 1.5F;
            Vec3 head = p.add(0, 2.55, 0);
            double dist = 2.5 - 0.6 * k;
            cam = head.add(f.scale(dist)).add(side.scale(0.55)).add(0, -0.45 + 0.03 * Math.sin(t * 40) * (1 - k), 0);
            target = head.add(0, -0.1, 0);
        } else if (t < 3.9F) {
            float k = (t - 1.5F) / 2.4F;
            double a = Math.toRadians(-10 + 22 * k);
            Vec3 dir = f.scale(Math.cos(a)).add(side.scale(Math.sin(a) + 0.75)).normalize();
            cam = p.add(dir.scale(6.4 - 0.6 * k)).add(0, 0.35, 0);
            target = p.add(0, 1.5 - 0.4 * k, 0);
        } else {
            float k = smooth01((t - 3.9F) / 3.0F);
            Vec3 dir = f.scale(-0.45).add(side.scale(-0.9)).normalize();
            cam = p.add(dir.scale(4.5 + 4.0 * k)).add(0, 1.4 + 4.6 * k, 0);
            target = p.add(0, 1.0, 0);
        }
        var hit = mc.level.clip(new net.minecraft.world.level.ClipContext(target, cam, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) cam = hit.getLocation().add(target.subtract(hit.getLocation()).normalize().scale(0.3));
        Vec3 d = target.subtract(cam);
        double cy = Math.toDegrees(Math.atan2(-d.x, d.z));
        double cp = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new double[]{cam.x, cam.y, cam.z, cy, cp};
    }

    private static float smooth01(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** Гибель на экране: кардиограмма пилота (удары реже → прямая линия), надпись, уход в темноту. */
    private static void deathOverlay(GuiGraphicsExtractor g, Minecraft mc, int w, int h, float a) {
        float t = (a - MechShowdown.DEATH_START) / 20.0F;
        float k = (a - MechShowdown.DEATH_START) / MechShowdown.DEATH;
        vignette(g, w, h, 0.45F + 0.45F * k);
        // Отказ систем: красные вспышки в такт мигающим огням.
        if (t < 3.2F) g.fill(0, 0, w, h, ARGB.color(0.18F * MechWornLayer.deathGlow(t) * (1 - t / 3.2F), 0xA00010));
        // Кардиограмма внизу: пики всё реже и слабее, после 3.6 с — прямая.
        int x0 = w / 2 - 110, x1 = w / 2 + 110, y0 = h - Math.round(h * LETTERBOX) - 34;
        float alpha = Math.min(1, t / 0.4F) * (1 - Mth.clamp((t - 5.2F) / 0.8F, 0, 1));
        int col = t < 3.6F ? 0x39FF6A : 0xFF3048;
        float head = (t * 70) % (x1 - x0);
        for (int x = x0; x < x1; x++) {
            float px = x - x0;
            float age = (head - px + (x1 - x0)) % (x1 - x0);   // сколько назад нарисован этот пиксель
            float tt = t - age / 70.0F;                        // время, когда он был нарисован
            if (tt < 0) continue;
            int y = y0;
            float beatGap = 0.7F + tt * 0.35F;
            float ph = tt % beatGap;
            if (tt < 3.6F && ph < 0.12F) {
                float amp = 14 * (1 - tt / 4.0F);
                y -= Math.round(amp * Mth.sin(ph / 0.12F * (float) Math.PI * 2) * (ph < 0.06F ? 1 : -0.4F));
            }
            float fade = 1 - age / (x1 - x0);
            g.fill(x, y, x + 1, y + 1, ARGB.color(alpha * fade, col));
            if (age < 2) g.fill(x - 1, y - 1, x + 2, y + 2, ARGB.color(alpha, 0xFFFFFF));
        }
        String label = t < 3.6F ? "ПУЛЬС ПИЛОТА" : "СИГНАЛ ПИЛОТА ПОТЕРЯН";
        float lf = t < 3.6F ? 0.7F : (0.6F + 0.4F * Mth.sin(t * 9));
        com.smi7i.sandevistan.client.HoloUi.centeredText(g, mc.font, label, w / 2, y0 - 26, col, alpha * lf);
        // В конце — медленно в темноту.
        g.fill(0, 0, w, h, ARGB.color(Mth.clamp((t - 4.8F) / 1.2F, 0, 1), 0x000000));
    }

    // ---------- Экран ----------

    public static void render(GuiGraphicsExtractor g, float partial) {
        Minecraft mc = Minecraft.getInstance();
        float a = age(mc.player, partial);
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        // Между концом кат-сцены гибели и своим экраном смерти — сплошной чёрный (никаких мельканий мира).
        // Гибель (в «Последнем бою» или любая в костюме): затемнения, кардиограмма, полосы кино.
        float dying = deathTime(mc.player, partial);
        if (dying >= 0 && (a < 0 || a >= MechShowdown.DEATH_START)) {
            deathOverlay(g, mc, w, h, MechShowdown.DEATH_START + dying * 20);
            int bar = Math.round(h * LETTERBOX);
            g.fill(0, 0, w, bar, 0xFF000000);
            g.fill(0, h - bar, w, h, 0xFF000000);
            return;
        }
        if (a < 0 && diedInShowdown && mc.player != null && mc.player.isDeadOrDying()) {
            g.fill(0, 0, w, h, 0xFF000000);
            return;
        }
        if (a < 0) return;
        if (a < MechShowdown.CUTSCENE) {
            cutscene(g, mc, partial, a, w, h);
        } else if (a < MechShowdown.DEATH_START) {
            speedLines(g, w, h, a, 1.0F);
        } else {
            deathOverlay(g, mc, w, h, a);
        }
        // Полосы кино — всю способность.
        int bar = Math.round(h * LETTERBOX);
        g.fill(0, 0, w, bar, 0xFF000000);
        g.fill(0, h - bar, w, h, 0xFF000000);
    }

    /**
     * Экранная часть кат-сцены — только поверх картинки мира (саму картинку снимает камера в мире, см. cutsceneCamera
     * и submitStage): затемнения, красные вспышки, глитчи, переходы через чёрное.
     */
    private static void cutscene(GuiGraphicsExtractor g, Minecraft mc, float partial, float a, int w, int h) {
        long ms = net.minecraft.util.Util.getMillis();
        if (a < FACE_END) {
            // 1. Лицо пилота в кабине: тёмно-синий свет; под конец — бегущие строки данных.
            g.fill(0, 0, w, h, ARGB.color(0.3F, 0x081436));
            vignette(g, w, h, 0.7F);
            if (a > FACE_END - 12) dataGlitch(g, mc, w, h, ms, (a - (FACE_END - 12)) / 12.0F);
            fadeFromBlack(g, w, h, a / 6.0F);
        } else if (a < BLACK1_END) {
            g.fill(0, 0, w, h, 0xFF000000);
            // Тяжёлые текстуры космоса рисуются здесь, пока экран чёрный, — чтобы не дёрнулась картинка на Луне.
            earthView(); milkyWay(); nebula(); regolith(); softDot(); starSpikes(); softRing();
        } else if (a < MOON_END) {
            // 2. Луна (в мире): виньетка как у объектива, киношное зерно, глитч данных и выход из темноты.
            vignette(g, w, h, 0.55F);
            Random grain = new Random(ms / 42);
            for (int i = 0; i < 700; i++) {
                int gx = grain.nextInt(w), gy = grain.nextInt(h);
                g.fill(gx, gy, gx + 1, gy + 1, ARGB.color(0.05F + grain.nextFloat() * 0.07F, grain.nextBoolean() ? 0xFFFFFF : 0x000000));
            }
            if (a > 140 && a < 150) dataGlitch(g, mc, w, h, ms, 1.0F);
            fadeFromBlack(g, w, h, (a - BLACK1_END) / 16.0F);
            float out = (a - (MOON_END - 4)) / 4.0F;
            if (out > 0) g.fill(0, 0, w, h, ARGB.color(Mth.clamp(out, 0, 1), 0x000000));
        } else if (a < DARK_RED_END) {
            // 3. Глитч: тёмно-красная кабина.
            g.fill(0, 0, w, h, ARGB.color(0.75F, 0x3A0000));
            tearBars(g, w, h, ms, 0xFF3020, 0.5F);
        } else if (a < BLACK2_END) {
            g.fill(0, 0, w, h, 0xFF000000);
        } else if (a < RED_END) {
            // 4. Красная вспышка.
            g.fill(0, 0, w, h, 0xFFE00808);
            tearBars(g, w, h, ms, 0xFF6040, 0.6F);
        } else if (a < SHAKE_END) {
            // 5. Лицо трясётся (камера дрожит), красный свет уходит.
            float k = (a - RED_END) / (SHAKE_END - RED_END);
            g.fill(0, 0, w, h, ARGB.color(0.5F * (1 - k), 0xC00000));
            vignette(g, w, h, 0.75F);
        } else if (a < SCREAM_END) {
            // 6. Крик: камера вплотную, сильная тряска.
            vignette(g, w, h, 0.65F);
            tearBars(g, w, h, ms, 0xFFFFFF, 0.25F);
        } else if (a < CLONES_END) {
            // 7. От головы во все стороны — лучи Сандевистана (в мире, submitBurst) + лёгкая зелёная дымка.
            g.fill(0, 0, w, h, ARGB.color(0.12F, 0x10C080));
            vignette(g, w, h, 0.55F);
        } else if (a < BLACK3_END) {
            g.fill(0, 0, w, h, 0xFF000000);
        } else {
            // 8. Мир — уже в Сандевистане: выход из темноты и линии скорости.
            float k = (a - BLACK3_END) / (MechShowdown.CUTSCENE - BLACK3_END);
            g.fill(0, 0, w, h, ARGB.color(0.9F * (1 - k), 0x000000));
            speedLines(g, w, h, a, k);
        }
    }

    // ---------- Камера кат-сцены (в мире) ----------

    /** Луна: где сцена (высоко над миром, над игроком), запоминается в начале кадра с Луной. */
    private static Vec3 stage;
    private static final double STAGE_Y = 1000;

    /** Камера кат-сцены: лицо пилота крупно (спереди, чуть снизу) или Луна. null — не кат-сцена. */
    public static double[] cutsceneCamera(float partial) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        float a = age(p, partial);
        if (a < 0 || a >= MechShowdown.CUTSCENE) return null;
        if (a >= BLACK1_END && a < MOON_END) {
            Vec3 o = stageOrigin(p);
            float k = (a - BLACK1_END) / (MOON_END - BLACK1_END);
            // За спинами сидящих, низко, смотрит чуть вверх — на Землю; очень медленно наезжает.
            Vec3 cam = o.add(0.15, 0.85, 3.9 - 0.5 * k);
            return new double[]{cam.x, cam.y, cam.z, 180.0, -9.0 + 1.5 * k};
        }
        // Лицо: камера перед пилотом (по взгляду), чуть ниже глаз, смотрит на лицо.
        Vec3 eye = p.getEyePosition(partial);
        float yaw = p.getYRot();
        float zoom;
        float shake = 0;
        if (a < FACE_END) zoom = 1.0F + 0.15F * (a / FACE_END);
        else if (a < SHAKE_END) { zoom = 1.25F; shake = 0.06F; }
        else if (a < SCREAM_END) { zoom = 1.7F; shake = 0.1F; }
        else { zoom = 1.08F; } // последний кадр — спокойно, как первый
        double dist = 2.1 / zoom;
        Vec3 fwd = Vec3.directionFromRotation(0, yaw);
        Vec3 cam = eye.add(fwd.scale(dist)).add(0, -0.35 / zoom, 0);
        if (shake > 0) cam = cam.add((R.nextDouble() - 0.5) * shake, (R.nextDouble() - 0.5) * shake, (R.nextDouble() - 0.5) * shake);
        Vec3 d = eye.add(0, -0.05, 0).subtract(cam);
        double cy = Math.toDegrees(Math.atan2(-d.x, d.z));
        double cp = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        return new double[]{cam.x, cam.y, cam.z, cy, cp};
    }

    /** Угол обзора: лицо — узкий (кино), Луна — шире; полёт — шире обычного. null — как обычно. */
    public static Float cutsceneFov() {
        Minecraft mc = Minecraft.getInstance();
        float a = age(mc.player, 0);
        if (a < 0 || a >= MechShowdown.CUTSCENE) return null;
        return a >= BLACK1_END && a < MOON_END ? 72.0F : 48.0F;
    }

    private static Vec3 stageOrigin(Player p) {
        if (stage == null) stage = new Vec3(Math.floor(p.getX()) + 0.5, STAGE_Y, Math.floor(p.getZ()) + 0.5);
        return stage;
    }

    /** Сцена с Луной или лучи Сандевистана от головы — есть что рисовать в мире. */
    public static boolean hasStage() {
        Minecraft mc = Minecraft.getInstance();
        float a = age(mc.player, 0);
        return (a >= BLACK1_END - 2 && a < MOON_END + 2) || (a >= SCREAM_END && a < CLONES_END + 1);
    }

    /**
     * Последний кадр: от головы пилота во все стороны вырываются лучи Сандевистана — разноцветные (как послеобразы),
     * вытягиваются наружу и тают; в центре — вспышка.
     */
    private static void submitBurst(net.minecraft.client.renderer.SubmitNodeCollector collector,
                                    net.minecraft.client.renderer.state.level.CameraRenderState camera, float partial, float a) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 head = mc.player.getEyePosition(partial).subtract(camera.pos);
        float k = Mth.clamp((a - SCREAM_END) / (CLONES_END - SCREAM_END), 0, 1);
        List<float[]> q = new java.util.ArrayList<>();
        Random r = new Random(9);
        for (int i = 0; i < 70; i++) {
            Vec3 dir = new Vec3(r.nextGaussian(), r.nextGaussian() * 0.8, r.nextGaussian()).normalize();
            float delay = r.nextFloat() * 0.35F;
            float t = Mth.clamp((k - delay) / 0.55F, 0, 1);
            if (t <= 0) continue;
            double start = 0.25 + 3.5 * t * t, len = 0.6 + 2.2 * (1 - t) * (0.5 + r.nextDouble());
            float fade = 1 - t;
            int rgb = Mth.hsvToRgb((0.3F + i * 0.07F) % 1.0F, 0.85F, 1.0F);
            Vec3 a0 = head.add(dir.scale(start)), a1 = head.add(dir.scale(start + len));
            ribbonQuad(q, a0, a1, 0.05F + 0.06F * fade, scaleRgb(rgb, fade));
            ribbonQuad(q, a0, a1, 0.18F + 0.1F * fade, scaleRgb(rgb, 0.3F * fade));
        }
        float flash = Math.max(0, 1 - k * 2.5F);
        if (flash > 0) {
            billboard(q, head, 1.2F + 2.5F * k, scaleRgb(0xB0FFD0, 0.8F * flash));
            billboard(q, head, 0.5F, scaleRgb(0xFFFFFF, flash));
        }
        collector.submitCustomGeometry(new com.mojang.blaze3d.vertex.PoseStack(),
                com.smi7i.sandevistan.client.GlowRender.type(), (pose, b) -> emitQuads(pose, b, q, false));
    }

    /** Лента от a до b к камере (камера в начале координат). */
    private static void ribbonQuad(List<float[]> out, Vec3 a, Vec3 b, float width, int rgb) {
        Vec3 side = b.subtract(a).cross(a.add(b).scale(0.5));
        if (side.lengthSqr() < 1.0E-8) return;
        side = side.normalize().scale(width / 2);
        addQuad(out, a.subtract(side), a.add(side), b.add(side), b.subtract(side), rgb);
    }

    // ---------- Сцена «Луна» в мире ----------

    private static final int FULL_BRIGHT = 15728880;
    /** Размер площадки грунта (блоки от центра) и насколько далеко висят фон с Землёй и звёзды. */
    private static final int GROUND_R = 72;
    /** Насколько фигуры откинулись назад на руки (градусы). */
    private static final float LEAN = 20.0F;
    // Ближе, чем начинается туман игры (иначе Земля и чёрное небо тонули в тумане — «серый космос, Земли нет»).
    private static final double BACKDROP = 40, SKY = 46, BOX = 52;
    /** Масштаб размеров звёзд и бликов (подбирались для дальности 150/140). */
    private static final float SKY_K = (float) (SKY / 150.0), FL = (float) (BACKDROP / 140.0);
    /** Радиус «изгиба» Луны: дальше поверхность уходит вниз — горизонт закругляется. */
    private static final double MOON_CURVE = 70.0;
    private static int[][] heights;
    private static net.minecraft.world.level.block.state.BlockState[][] blocks;

    /** Всё на отрисовку: чёрный «купол», звёзды, туманности, Земля, солнечный блик, грунт из блоков, две фигуры, блики. */
    public static void submitStage(net.minecraft.client.renderer.SubmitNodeCollector collector,
                                   net.minecraft.client.renderer.state.level.CameraRenderState camera, float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (!hasStage() || mc.player == null) return;
        float phase = age(mc.player, partial);
        if (phase >= SCREAM_END) {
            submitBurst(collector, camera, partial, phase);
            return;
        }
        Vec3 o = stageOrigin(mc.player);
        Vec3 cam = camera.pos;
        long ms = net.minecraft.util.Util.getMillis();
        float a = age(mc.player, partial);
        float k = Mth.clamp((a - BLACK1_END) / (MOON_END - BLACK1_END), 0, 1);
        com.mojang.blaze3d.vertex.PoseStack ps = new com.mojang.blaze3d.vertex.PoseStack();
        Vec3 c = o.subtract(cam);

        // Чёрный «купол» вокруг сцены (закрывает небо и мир внизу).
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.textBackground(), (pose, b) -> {
            int col = 0xFF020309;
            double r = BOX;
            double[][] f = {{-r, -r, -r, r, -r, -r, r, r, -r, -r, r, -r}, {-r, -r, r, -r, r, r, r, r, r, r, -r, r},
                    {-r, -r, -r, -r, r, -r, -r, r, r, -r, -r, r}, {r, -r, -r, r, -r, r, r, r, r, r, r, -r},
                    {-r, r, -r, r, r, -r, r, r, r, -r, r, r}, {-r, -r, -r, -r, -r, r, r, -r, r, r, -r, -r}};
            for (double[] q : f) {
                for (int i = 0; i < 4; i++) b.addVertex(pose, (float) (c.x + q[i * 3]), (float) (c.y + q[i * 3 + 1]), (float) (c.z + q[i * 3 + 2])).setColor(col).setLight(FULL_BRIGHT);
                for (int i = 3; i >= 0; i--) b.addVertex(pose, (float) (c.x + q[i * 3]), (float) (c.y + q[i * 3 + 1]), (float) (c.z + q[i * 3 + 2])).setColor(col).setLight(FULL_BRIGHT);
            }
        });
        // ===== Космос: глубокий, тёмный, контрастный =====
        double[] camData = cutsceneCamera(partial);
        Vec3 look = Vec3.directionFromRotation((float) camData[4], (float) camData[3]);
        Vec3 right = look.cross(new Vec3(0, 1, 0)).normalize();
        Vec3 up = right.cross(look).normalize();
        double halfH = BACKDROP * Math.tan(Math.toRadians(cutsceneFov() / 2.0)) * 1.08;
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double halfW = halfH * aspect;
        // Млечный Путь — широкая светящаяся полоса с тёмными прожилками пыли, по диагонали через небо.
        List<float[]> milky = new java.util.ArrayList<>();
        Vec3 mwC = look.scale(SKY).add(up.scale(halfH * 0.15)).add(right.scale(-halfW * 0.1));
        Vec3 mwA = right.scale(Math.cos(0.42)).add(up.scale(Math.sin(0.42))).normalize().scale(halfW * 1.6);
        Vec3 mwB = right.scale(-Math.sin(0.42)).add(up.scale(Math.cos(0.42))).normalize().scale(halfH * 0.75);
        addQuad(milky, mwC.subtract(mwA).add(mwB), mwC.add(mwA).add(mwB), mwC.add(mwA).subtract(mwB), mwC.subtract(mwA).subtract(mwB), 0xFFFFFF);
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(milkyWay()), (pose, b) -> emitTextured(pose, b, milky));
        // Звёзды: тысячи — от еле заметной пыли до ярких с дифракционными лучами; цвет по температуре; мерцают.
        List<float[]> stars = new java.util.ArrayList<>();
        List<float[]> bigStars = new java.util.ArrayList<>();
        Random sr = new Random(77);
        int[] starCols = {0x9FBFFF, 0xC8D8FF, 0xFFFFFF, 0xFFF4E0, 0xFFD9A8, 0xFFB080};
        for (int i = 0; i < 1600; i++) {
            double th = sr.nextDouble() * Math.PI * 2, ph = Math.acos(sr.nextDouble() * 1.7 - 0.7);
            Vec3 at = new Vec3(Math.sin(ph) * Math.cos(th), Math.cos(ph), Math.sin(ph) * Math.sin(th)).scale(SKY);
            double mag = Math.pow(sr.nextDouble(), 7); // много тусклых, мало ярких
            float tw = 0.75F + 0.25F * (float) Math.sin(ms / (300.0 + sr.nextInt(900)) + i * 1.7);
            int col = starCols[sr.nextInt(starCols.length)];
            float size = (float) (0.18 + 1.3 * mag) * SKY_K * 1.6F;
            float br = (float) (0.25 + 0.75 * Math.sqrt(mag)) * tw;
            texBillboard(stars, at, size, scaleRgb(col, br), 0);
            if (mag > 0.35) texBillboard(bigStars, at, size * 7, scaleRgb(col, 0.6F * br), (float) (sr.nextDouble() * 0.3));
        }
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(softDot()), (pose, b) -> emitTextured(pose, b, stars));
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(starSpikes()), (pose, b) -> emitTextured(pose, b, bigStars));
        // Туманности — огромные еле заметные облака цвета.
        List<float[]> neb = new java.util.ArrayList<>();
        int[] nebCols = {0x2A1048, 0x0A2A44, 0x401030, 0x102850};
        for (int i = 0; i < 6; i++) {
            Vec3 at = new Vec3(Math.cos(i * 1.9 + 0.4), 0.2 + 0.25 * Math.sin(i * 1.3), Math.sin(i * 1.9 + 0.4)).normalize().scale(SKY - 3);
            texBillboard(neb, at, (70 + 25 * (i % 3)) * SKY_K, nebCols[i % nebCols.length], i * 0.7F);
        }
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(nebula()), (pose, b) -> emitTextured(pose, b, neb));

        // ===== Земля во весь верх кадра (как в кадре из аниме) =====
        Vec3 bc = look.scale(BACKDROP);
        Vec3 p0 = bc.subtract(right.scale(halfW)).add(up.scale(halfH)), p1 = bc.add(right.scale(halfW)).add(up.scale(halfH));
        Vec3 p2 = bc.add(right.scale(halfW)).subtract(up.scale(halfH)), p3 = bc.subtract(right.scale(halfW)).subtract(up.scale(halfH));
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucentEmissive(earthView()), (pose, b) -> {
            Vec3[] pts = {p0, p1, p2, p3};
            float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < 4; i++) {
                b.addVertex(pose, (float) pts[i].x, (float) pts[i].y, (float) pts[i].z).setColor(0xFFFFFFFF).setUv(uv[i][0], uv[i][1])
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0, 0, 1);
            }
        });

        // ===== Солнце встаёт из-за края Земли: ослепительное ядро, лучи, анаморфная полоса, «зайчики» объектива =====
        // Точка на дуге края Земли справа (дуга в earthView: центр x 0.55, низ 0.68 кадра, радиус 1.45 ширины).
        double sunU = 0.80, rad = 1.45, cxU = 0.55, cyV = 0.68 - rad * VIEW_W / (double) VIEW_H;
        double dyLimb = Math.sqrt(Math.max(0, rad * rad - Math.pow((sunU - cxU) * 1.0, 2))) * VIEW_W / (double) VIEW_H;
        double sunV = cyV + dyLimb + 0.012;
        Vec3 sun = bc.add(right.scale((sunU * 2 - 1) * halfW)).add(up.scale((1 - sunV * 2) * halfH)).scale(0.97);
        float rise = Mth.clamp(0.55F + 0.45F * k, 0, 1); // за сцену солнце разгорается
        float flick = 0.97F + 0.03F * (float) Math.sin(ms / 130.0);
        List<float[]> flareSoft = new java.util.ArrayList<>();
        List<float[]> flareSpikes = new java.util.ArrayList<>();
        List<float[]> flareRings = new java.util.ArrayList<>();
        texBillboard(flareSoft, sun, 70 * FL * rise, scaleRgb(0xFFB060, 0.35F * rise), 0);   // широкое тёплое свечение
        texBillboard(flareSoft, sun, 26 * FL * rise, scaleRgb(0xFFE6B8, 0.85F * rise), 0);   // ореол
        texBillboard(flareSoft, sun, 7 * FL * flick, scaleRgb(0xFFFFFF, 1.0F), 0);           // ядро
        texBillboard(flareSpikes, sun, 60 * FL * rise, scaleRgb(0xFFF0D8, 0.75F * rise), (float) (ms / 9000.0));
        texBillboard(flareSpikes, sun, 38 * FL * rise, scaleRgb(0xFFFFFF, 0.5F * rise), (float) (0.4 - ms / 13000.0));
        // Анаморфная полоса — тонкая голубая черта через весь кадр (как у кинообъектива).
        Vec3 sAx = right.scale(halfW * 2.4), sAy = up.scale(1.1 * FL);
        addQuad(flareSoft, sun.subtract(sAx).add(sAy), sun.add(sAx).add(sAy), sun.add(sAx).subtract(sAy), sun.subtract(sAx).subtract(sAy), scaleRgb(0x6FB8FF, 0.55F * rise));
        Vec3 sAy2 = up.scale(0.35 * FL);
        addQuad(flareSoft, sun.subtract(sAx.scale(0.5)).add(sAy2), sun.add(sAx.scale(0.5)).add(sAy2), sun.add(sAx.scale(0.5)).subtract(sAy2), sun.subtract(sAx.scale(0.5)).subtract(sAy2), scaleRgb(0xFFFFFF, 0.6F * rise));
        // «Зайчики» — кольца и пятна на линии от солнца через центр кадра.
        Vec3 center = look.scale(BACKDROP * 0.97);
        float[] ghostAt = {0.25F, 0.55F, 0.8F, 1.35F, 1.7F, 2.1F};
        float[] sz = {5, 11, 3, 18, 7, 26};
        int[] gc = {0x60FFC0, 0x5080FF, 0xFFC070, 0x8060FF, 0x60D0FF, 0xFF7090};
        for (int i = 0; i < ghostAt.length; i++) {
            Vec3 pnt = sun.lerp(center, ghostAt[i]);
            texBillboard(i % 2 == 0 ? flareSoft : flareRings, pnt, sz[i] * FL, scaleRgb(gc[i], (i % 2 == 0 ? 0.12F : 0.2F) * rise), 0);
        }
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(softDot()), (pose, b) -> emitTextured(pose, b, flareSoft));
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(starSpikes()), (pose, b) -> emitTextured(pose, b, flareSpikes));
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(softRing()), (pose, b) -> emitTextured(pose, b, flareRings));

        // ===== Лунный грунт: гладкая поверхность с изгибом к горизонту (как у шара), своя текстура реголита =====
        // Высота падает как у шара радиуса MOON_CURVE; солнце светит из-за спины камеры; вдали — темнее.
        Vec3 sunDir = new Vec3(0.15, 0.62, 0.77).normalize();
        int gr0 = 44;
        List<float[]> ground = new java.util.ArrayList<>(); // x,y,z, u,v, цвет — по вершинам, квадратами
        for (int x = -gr0; x < gr0; x++) {
            for (int z = -gr0; z < 12; z++) {
                float[] quad = new float[24];
                int[][] corners = {{x, z}, {x, z + 1}, {x + 1, z + 1}, {x + 1, z}};
                for (int k2 = 0; k2 < 4; k2++) {
                    int gx = corners[k2][0], gz = corners[k2][1];
                    double hy = moonHeight(gx, gz);
                    // Нормаль по соседним высотам.
                    double nx = moonHeight(gx - 1, gz) - moonHeight(gx + 1, gz), nz = moonHeight(gx, gz - 1) - moonHeight(gx, gz + 1);
                    Vec3 n = new Vec3(nx, 2.0, nz).normalize();
                    double lit = 0.42 + 0.68 * Math.max(0, n.dot(sunDir));
                    double dist = Math.hypot(gx, gz * 1.1);
                    double fade = Mth.clamp(1.0 - (dist - 14) / 30.0, 0.0, 1.0);
                    int g = (int) Mth.clamp(255 * lit * (0.06 + 0.94 * fade * fade), 0, 255);
                    quad[k2 * 6] = (float) (c.x + gx);
                    quad[k2 * 6 + 1] = (float) (c.y + hy);
                    quad[k2 * 6 + 2] = (float) (c.z + gz);
                    quad[k2 * 6 + 3] = gx * 0.25F;
                    quad[k2 * 6 + 4] = gz * 0.25F;
                    quad[k2 * 6 + 5] = Float.intBitsToFloat(0xFF000000 | (g << 16) | (g << 8) | Math.min(255, g + 3));
                }
                ground.add(quad);
            }
        }
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.entityCutout(regolith()), (pose, b) -> {
            for (float[] q : ground) {
                for (int k2 = 0; k2 < 4; k2++) {
                    b.addVertex(pose, q[k2 * 6], q[k2 * 6 + 1], q[k2 * 6 + 2]).setColor(Float.floatToRawIntBits(q[k2 * 6 + 5]))
                            .setUv(q[k2 * 6 + 3], q[k2 * 6 + 4]).setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                            .setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
                }
            }
        });        // Космическая пыль — мелкие искорки медленно плывут у самой камеры и вспыхивают на солнце.
        List<float[]> dust = new java.util.ArrayList<>();
        Random dr = new Random(5);
        double tSec = ms / 1000.0;
        for (int i = 0; i < 260; i++) {
            double bxp = dr.nextDouble() * 24 - 12, byp = dr.nextDouble() * 9 - 2, bzp = dr.nextDouble() * 24 - 20;
            double vx = (dr.nextDouble() - 0.5) * 0.25, vy = 0.02 + dr.nextDouble() * 0.08, vz = (dr.nextDouble() - 0.5) * 0.2;
            double px = wrap(bxp + vx * tSec, -12, 12), py = wrap(byp + vy * tSec, -2, 7), pz = wrap(bzp + vz * tSec, -20, 4);
            Vec3 pp = c.add(px, py, pz);
            double dist = pp.length();
            if (dist < 0.6) continue;
            float sparkle = (float) Math.pow(Math.max(0, Math.sin(tSec * (0.6 + dr.nextDouble()) + i)), 8);
            float br = (float) Mth.clamp(0.25 + 0.75 * sparkle, 0, 1) * (float) Mth.clamp(1.4 - dist / 18, 0.15, 1);
            texBillboard(dust, pp, (float) (0.02 + 0.03 * dr.nextDouble() + 0.05 * sparkle), scaleRgb(dr.nextInt(4) == 0 ? 0xFFE0C0 : 0xD8E8FF, br), 0);
        }
        collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(softDot()), (pose, b) -> emitTextured(pose, b, dust));
        // Две фигуры сидят спиной к камере на краю грунта и смотрят на Землю.
        for (int i = 0; i < 2; i++) {
            AvatarRenderState st = figureState(mc, partial, i == 1);
            if (st == null) continue;
            double fx = o.x + (i == 0 ? -1.05 : 1.0), fz = o.z;
            double fy = o.y - 0.62; // бёдра на грунте
            st.x = fx;
            st.y = fy;
            st.z = fz;
            // Откинулись назад на руки: вся фигура наклонена назад (к камере) на LEAN вокруг бёдер.
            com.mojang.blaze3d.vertex.PoseStack fps = new com.mojang.blaze3d.vertex.PoseStack();
            double hip = 0.70;
            fps.translate(fx - cam.x, fy - cam.y + hip, fz - cam.z);
            fps.mulPose(com.mojang.math.Axis.XP.rotationDegrees(LEAN));
            fps.translate(0, -hip, 0);
            mc.getEntityRenderDispatcher().submit(st, camera, 0, 0, 0, fps, collector);
        }
        // Блики на скафандрах: короткие звёздочки-искры то на плече, то на голове.
        List<float[]> glints = new java.util.ArrayList<>();
        Random gr = new Random(ms / 140);
        for (int i = 0; i < 2; i++) {
            if (gr.nextFloat() > 0.45F) continue;
            double fx = (i == 0 ? -1.05 : 1.0) + (gr.nextDouble() - 0.5) * 0.5;
            Vec3 at = new Vec3(c.x + fx, c.y + 0.6 + gr.nextDouble() * 0.7, c.z + 0.15);
            float s = 0.5F + gr.nextFloat() * 0.5F;
            texBillboard(glints, at, s, scaleRgb(0xEAF4FF, 0.9F), gr.nextFloat());
        }
        if (!glints.isEmpty()) {
            collector.submitCustomGeometry(ps, net.minecraft.client.renderer.rendertype.RenderTypes.eyes(starSpikes()), (pose, b) -> emitTextured(pose, b, glints));
        }
    }

    /**
     * Высота лунной поверхности в точке (x, z) от места, где сидят: изгиб шара (дальше — ниже)
     * и еле заметные пологие волны (у фигур и камеры — ровно).
     */
    private static double moonHeight(double x, double z) {
        double d2 = x * x + z * z;
        double curve = -d2 / (2 * MOON_CURVE);
        double calm = Mth.clamp((Math.sqrt(d2) - 6) / 10.0, 0, 1);
        double waves = (fbm(x * 0.06 + 5, z * 0.06 + 2, 3) - 0.5) * 1.6 * calm;
        return curve + waves;
    }

    /** Состояние сидящей фигуры (как у игрока, но без костюма), спиной к камере (лицом к -Z), смотрит вверх. */
    private static AvatarRenderState figureState(Minecraft mc, float partial, boolean partner) {
        AbstractClientPlayer p = mc.player;
        if (p == null) return null;
        AvatarRenderState st = (AvatarRenderState) mc.getEntityRenderDispatcher().getRenderer(p).createRenderState(p, partial);
        prepare(st);
        st.lightCoords = FULL_BRIGHT;
        st.setData(MechWornLayer.IN_MECH, false);
        st.setData(MechWornLayer.STAGE_SIT, true);
        st.isPassenger = true;
        st.bodyRot = 180.0F; // лицом к -Z (к Земле), спиной к камере
        st.yRot = partner ? -10.0F : 10.0F;
        st.xRot = -12.0F; // корпус откинут назад — голова и так смотрит вверх, на Землю
        st.walkAnimationSpeed = 0;
        // Шлем скафандра — стеклянный «аквариум» на голове.
        st.headItem.clear();
        mc.getItemModelResolver().updateForLiving(st.headItem, new ItemStack(Items.GLASS), ItemDisplayContext.HEAD, p);
        if (partner) {
            st.skin = PlayerSkin.insecure(new ClientAsset.ResourceTexture(PARTNER_SKIN, PARTNER_SKIN), null, null, PlayerModelType.WIDE);
            var implant = st.getData(com.smi7i.sandevistan.client.ImplantBackLayer.IMPLANT_ITEM);
            if (implant != null) implant.clear();
            var implantGlow = st.getData(com.smi7i.sandevistan.client.ImplantBackLayer.IMPLANT_GLOW);
            if (implantGlow != null) implantGlow.clear();
        }
        return st;
    }

    /** Высоты и блоки площадки: в основном ровно, редкие кочки, кратеры (углубления с бортиком). */
    private static void buildGround() {
        if (heights != null) return;
        int n = GROUND_R * 2 + 1;
        heights = new int[n][n];
        blocks = new net.minecraft.world.level.block.state.BlockState[n][n];
        var white = net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.pick(net.minecraft.world.item.DyeColor.WHITE).defaultBlockState();
        var gray = net.minecraft.world.level.block.Blocks.CONCRETE_POWDER.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY).defaultBlockState();
        var stone = net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState();
        var rock = net.minecraft.world.level.block.Blocks.ANDESITE.defaultBlockState();
        Random r = new Random(11);
        // Кратеры: круглая чаша на 1 блок вниз и бортик на 1 блок вверх — сразу читается «Луна».
        double[][] craters = new double[18][];
        for (int i = 0; i < craters.length; i++) {
            craters[i] = new double[]{(r.nextDouble() - 0.5) * 120, -10 - r.nextDouble() * 60, 2.5 + r.nextDouble() * 5};
        }
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) {
                double wx = x - GROUND_R, wz = z - GROUND_R;
                int h = 0;
                boolean inCrater = false;
                for (double[] cr : craters) {
                    double d = Math.hypot(wx - cr[0], wz - cr[1]);
                    if (d < cr[2]) { h = -1; inCrater = true; }
                    else if (d < cr[2] + 1.3 && !inCrater) h = 1;
                }
                // Редкие камни.
                if (h == 0 && r.nextInt(140) == 0) h = 1;
                if (Math.abs(wx) <= 6 && wz >= -4 && wz <= 14) h = 0; // где сидят и где камера — ровно
                heights[x][z] = h;
                double n2 = fbm(wx * 0.11 + 20, wz * 0.11, 3);
                blocks[x][z] = h > 0 ? (r.nextBoolean() ? rock : stone) : inCrater ? gray : n2 > 0.55 ? white : gray;
            }
        }
    }
    /** Грань блока грунта: kind 1 — верх, 2..5 — стенки (+X, -X, +Z, -Z). q = {x, y, z (угол блока), u0, v0, u1, v1, kind}. */
    private static void groundFace(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer b, float[] q) {
        float x = q[0], y = q[1], z = q[2], u0 = q[3], v0 = q[4], u1 = q[5], v1 = q[6];
        int kind = (int) q[7];
        float[][] v;
        float nx = 0, ny = 0, nz = 0;
        int shade;
        switch (kind) {
            case 1 -> { v = new float[][]{{x, y, z}, {x, y, z + 1}, {x + 1, y, z + 1}, {x + 1, y, z}}; ny = 1; shade = 0xFFF2F1EE; }
            case 2 -> { v = new float[][]{{x + 1, y, z}, {x + 1, y, z + 1}, {x + 1, y - 1, z + 1}, {x + 1, y - 1, z}}; nx = 1; shade = 0xFFA8ACB8; }
            case 3 -> { v = new float[][]{{x, y, z + 1}, {x, y, z}, {x, y - 1, z}, {x, y - 1, z + 1}}; nx = -1; shade = 0xFFA8ACB8; }
            case 4 -> { v = new float[][]{{x + 1, y, z + 1}, {x, y, z + 1}, {x, y - 1, z + 1}, {x + 1, y - 1, z + 1}}; nz = 1; shade = 0xFFDCDDE4; }
            default -> { v = new float[][]{{x, y, z}, {x + 1, y, z}, {x + 1, y - 1, z}, {x, y - 1, z}}; nz = -1; shade = 0xFF3E4252; } // в тени: солнце за спиной камеры
        }
        float[][] uv = {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        if (q.length > 8) shade = ARGB.scaleRGB(shade, q[8]) | 0xFF000000;
        for (int i = 0; i < 4; i++) {
            b.addVertex(pose, v[i][0], v[i][1], v[i][2]).setColor(shade).setUv(uv[i][0], uv[i][1])
                    .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, nx, ny, nz);
        }
    }

    /** Квадрат-«огонёк» к камере (камера в начале координат). */
    private static void billboard(List<float[]> out, Vec3 c, float size, int rgb) {
        Vec3 toCam = c.scale(-1).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0E-6) u = new Vec3(1, 0, 0);
        u = u.normalize().scale(size / 2);
        Vec3 v = u.cross(toCam).normalize().scale(size / 2);
        addQuad(out, c.subtract(u).add(v), c.add(u).add(v), c.add(u).subtract(v), c.subtract(u).subtract(v), rgb);
    }

    /** Луч-полоса к камере: горизонтальная или вертикальная. */
    private static void billboardStreak(List<float[]> out, Vec3 c, float length, float width, int rgb, boolean horizontal) {
        Vec3 toCam = c.scale(-1).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0E-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = u.cross(toCam).normalize();
        Vec3 a = horizontal ? u.scale(length / 2) : v.scale(length / 2);
        Vec3 bb = horizontal ? v.scale(width / 2) : u.scale(width / 2);
        addQuad(out, c.subtract(a).add(bb), c.add(a).add(bb), c.add(a).subtract(bb), c.subtract(a).subtract(bb), rgb);
    }

    private static void addQuad(List<float[]> out, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int rgb) {
        int col = 0xFF000000 | rgb;
        out.add(new float[]{(float) a.x, (float) a.y, (float) a.z, (float) b.x, (float) b.y, (float) b.z,
                (float) c.x, (float) c.y, (float) c.z, (float) d.x, (float) d.y, (float) d.z, Float.intBitsToFloat(col)});
    }

    private static void emitQuads(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer b, List<float[]> quads, boolean light) {
        for (float[] q : quads) {
            int col = Float.floatToRawIntBits(q[12]);
            int[] order = {0, 1, 2, 3, 3, 2, 1, 0};
            for (int k : order) {
                if (!light) com.smi7i.sandevistan.client.GlowRender.vertex(b, pose, q[k * 3], q[k * 3 + 1], q[k * 3 + 2], col);
                else b.addVertex(pose, q[k * 3], q[k * 3 + 1], q[k * 3 + 2]).setColor(col);
            }
        }
    }

    private static int scaleRgb(int rgb, float k) {
        k = Mth.clamp(k, 0, 1);
        return (Math.round(((rgb >> 16) & 0xFF) * k) << 16) | (Math.round(((rgb >> 8) & 0xFF) * k) << 8) | Math.round((rgb & 0xFF) * k);
    }
    /** Убрать лишнее у состояния для рисования в интерфейсе. */
    private static void prepare(AvatarRenderState st) {
        st.shadowPieces.clear();
        st.outlineColor = 0;
        st.nameTag = null;
        st.setData(MechWornLayer.SHOWDOWN_GLITCH, false);
        st.setData(MechWornLayer.STAGE, true);
        st.setData(MechWornLayer.FIRST_PERSON, false);
    }

    // ---------- Экранные эффекты ----------

    private static void fadeFromBlack(GuiGraphicsExtractor g, int w, int h, float k) {
        k = Mth.clamp(k, 0, 1);
        if (k < 1) g.fill(0, 0, w, h, ARGB.color(1 - k, 0x000000));
    }

    private static void vignette(GuiGraphicsExtractor g, int w, int h, float strength) {
        for (int i = 0; i < 10; i++) {
            float a = strength * 0.1F * (10 - i) / 10.0F;
            int e = Math.round(Math.min(w, h) * 0.32F * (i + 1) / 10.0F);
            int c = ARGB.color(a, 0x000000);
            g.fill(0, 0, w, e, c);
            g.fill(0, h - e, w, h, c);
            g.fill(0, 0, e, h, c);
            g.fill(w - e, 0, w, h, c);
        }
    }

    /** Глитч «данные»: бегущие столбцы шестнадцатеричных строк и голубые полосы. */
    private static void dataGlitch(GuiGraphicsExtractor g, Minecraft mc, int w, int h, long ms, float k) {
        Random gr = new Random(ms / 60);
        for (int row = 0; row < 14; row++) {
            if (gr.nextFloat() > 0.65F) continue;
            int y = Math.round(h * 0.1F) + row * 9 + gr.nextInt(3);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 40; j++) sb.append(Integer.toHexString(gr.nextInt(16)).toUpperCase()).append(gr.nextInt(5) == 0 ? " " : "");
            HoloUi.text(g, mc.font, sb.toString(), gr.nextInt(Math.max(1, w / 3)), y, 0x5FF0FF, 0.75F * k);
        }
        tearBars(g, w, h, ms, 0x40E0FF, 0.35F * k);
    }

    /** Рваные горизонтальные полосы. */
    private static void tearBars(GuiGraphicsExtractor g, int w, int h, long ms, int rgb, float alpha) {
        Random gr = new Random(ms / 45 * 31);
        for (int i = 0; i < 9; i++) {
            if (gr.nextFloat() > 0.6F) continue;
            int y = gr.nextInt(h), bh = 1 + gr.nextInt(5);
            int x = gr.nextInt(w / 2) - w / 4;
            g.fill(x, y, x + w / 3 + gr.nextInt(w), y + bh, ARGB.color(alpha * (0.4F + 0.6F * gr.nextFloat()), rgb));
        }
    }

    /** Линии скорости от краёв к центру (Сандевистан). */
    private static void speedLines(GuiGraphicsExtractor g, int w, int h, float age, float strength) {
        Random lr = new Random((long) (age * 3));
        float cx = w / 2.0F, cy = h / 2.0F;
        for (int i = 0; i < 46; i++) {
            double ang = lr.nextDouble() * Math.PI * 2;
            float r0 = (float) (Math.max(w, h) * (0.32 + lr.nextDouble() * 0.2));
            float len = (float) (Math.max(w, h) * (0.12 + lr.nextDouble() * 0.22));
            float x0 = cx + (float) Math.cos(ang) * r0, y0 = cy + (float) Math.sin(ang) * r0;
            int steps = Math.max(2, Math.round(len / 3));
            int col = ARGB.color(strength * (0.18F + lr.nextFloat() * 0.25F), lr.nextInt(4) == 0 ? 0xB0FFC0 : 0xFFFFFF);
            for (int s = 0; s < steps; s++) {
                float t = s / (float) steps;
                int x = Math.round(x0 + (float) Math.cos(ang) * len * t), y = Math.round(y0 + (float) Math.sin(ang) * len * t);
                g.fill(x, y, x + 1, y + 1, col);
            }
        }
    }

    // ---------- Текстуры Земли и Луны (рисуются кодом при первом показе) ----------

    private static final int EARTH_SIZE = 512, MOON_W = 512, MOON_H = 128;
    private static Identifier earthTex, moonTex;

    private static Identifier earth() {
        if (earthTex != null) return earthTex;
        NativeImage img = new NativeImage(EARTH_SIZE, EARTH_SIZE, true);
        float c = EARTH_SIZE / 2.0F, rad = EARTH_SIZE * 0.47F;
        Vector3f light = new Vector3f(-0.55F, 0.45F, 0.7F).normalize();
        for (int y = 0; y < EARTH_SIZE; y++) {
            for (int x = 0; x < EARTH_SIZE; x++) {
                float dx = (x - c) / rad, dy = (y - c) / rad;
                float d2 = dx * dx + dy * dy;
                if (d2 > 1.0F) {
                    // Голубое свечение атмосферы вокруг.
                    float halo = 1.0F - (float) Math.sqrt(d2);
                    float ha = Mth.clamp((halo + 0.07F) / 0.07F, 0, 1);
                    if (ha > 0) img.setPixel(x, y, ARGB.color(ha * ha * 0.7F, 0x6FB8FF));
                    continue;
                }
                float dz = (float) Math.sqrt(1 - d2);
                // Координаты на шаре → шум материков и облаков.
                double lon = Math.atan2(dx, dz) * 1.6, lat = Math.asin(-dy) * 1.6;
                double land = fbm(lon * 2.2 + 11, lat * 2.2 + 3, 5);
                double cloud = fbm(lon * 4.0 + 50, lat * 6.0 + 20, 5);
                int base;
                if (land > 0.56) {
                    double t = Mth.clamp((land - 0.56) / 0.2, 0, 1);
                    base = lerpRgb((float) t, 0x2E6B3A, 0x8A7A52);
                } else {
                    base = lerpRgb((float) Mth.clamp((0.56 - land) / 0.3, 0, 1), 0x2A73B8, 0x0B2E6E);
                }
                double cl = Mth.clamp((cloud - 0.5) / 0.22, 0, 1);
                base = lerpRgb((float) (cl * 0.9), base, 0xF2F6FA);
                float lambert = Math.max(0, dx * -light.x() + dy * -light.y() + dz * light.z());
                lambert = Math.max(lambert, 0.07F);
                float rim = (float) Math.pow(1 - dz, 3) * 0.6F; // голубая дымка по краю
                int lit = ARGB.scaleRGB(base, Math.min(1.0F, 0.15F + lambert * 1.05F));
                lit = lerpRgb(rim, lit, 0x8FCBFF);
                img.setPixel(x, y, ARGB.color(1.0F, lit));
            }
        }
        earthTex = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/showdown_earth");
        Minecraft.getInstance().getTextureManager().register(earthTex, new DynamicTexture(() -> "sandevistan earth", img));
        return earthTex;
    }

    // Вид Земли целиком под кадр (как в аниме): картинка размером с экран, прозрачная вне шара.
    private static final int VIEW_W = 1280, VIEW_H = 720;
    private static Identifier earthViewTex;

    /**
     * Земля во весь кадр (как в кадре из аниме): огромный шар, низ дуги на 0.68 высоты кадра. Свет почти от зрителя
     * и снизу: середина ярко освещена (тёплая), края уходят в ночь. Материки, вытянутые завихрения облаков с тенями,
     * солнечный блик на океане, тонкая голубая атмосфера по краю и мягкое свечение за краем.
     */
    private static Identifier earthView() {
        if (earthViewTex != null) return earthViewTex;
        NativeImage img = new NativeImage(VIEW_W, VIEW_H, true);
        double rad = 1.45 * VIEW_W, cx = 0.55 * VIEW_W, cy = 0.68 * VIEW_H - rad;
        double lx = 0.18, ly = 0.7, lz = 0.69, ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ll; ly /= ll; lz /= ll;
        // Полувектор для блика (зритель смотрит вдоль +z).
        double hx = lx, hy = ly, hz = lz + 1, hl = Math.sqrt(hx * hx + hy * hy + hz * hz);
        hx /= hl; hy /= hl; hz /= hl;
        for (int y = 0; y < VIEW_H; y++) {
            for (int x = 0; x < VIEW_W; x++) {
                double nx = (x - cx) / rad, ny = (y - cy) / rad;
                double d2 = nx * nx + ny * ny;
                if (d2 > 1.0) {
                    // Свечение атмосферы за краем: яркое у самого края, быстро тает.
                    double out = (Math.sqrt(d2) - 1.0) * rad;
                    double lit = Math.max(0, nx * lx + ny * ly) * 1.6 + 0.25;
                    float ha = (float) (Math.exp(-out / 7.0) * 0.9 + Math.exp(-out / 30.0) * 0.25) * (float) Math.min(1, lit);
                    if (ha > 0.003F) img.setPixel(x, y, ARGB.color(Math.min(1, ha), 0x6CC4FF));
                    continue;
                }
                double nz = Math.sqrt(1 - d2);
                double lon = Math.atan2(nx, nz) * 3.0, lat = Math.asin(ny) * 3.0;
                double land = fbm(lon * 2.0 + 11, lat * 2.6 + 3, 6);
                double swirl = fbm(lon * 2 + 9, lat * 2, 3);
                double cloud = fbm(lon * 3.2 + 50 + 0.7 * swirl, lat * 7.0 + 20 + 0.4 * swirl, 6);
                double cloudShadow = fbm(lon * 3.2 + 50.04 + 0.7 * swirl, lat * 7.0 + 20.06 + 0.4 * swirl, 4);
                boolean ocean = land <= 0.58;
                int base = !ocean ? lerpRgb((float) ((land - 0.58) / 0.2), 0x2C5530, 0x8A7650)
                        : lerpRgb((float) ((0.58 - land) / 0.3), 0x1E5A8E, 0x061A38);
                if (!ocean && land > 0.74) base = lerpRgb((float) ((land - 0.74) / 0.08), base, 0xB8A98A); // пустыни
                if (Math.abs(ny) > 0.72) base = lerpRgb((float) ((Math.abs(ny) - 0.72) / 0.1), base, 0xE8F0F6); // льды
                double cl = Mth.clamp((cloud - 0.46) / 0.2, 0, 1);
                double sh = Mth.clamp((cloudShadow - 0.46) / 0.2, 0, 1) * (1 - cl);
                base = ARGB.scaleRGB(base, (float) (1 - 0.35 * sh)); // тени облаков на поверхности
                base = lerpRgb((float) (cl * 0.96), base, 0xF6F3EC);
                double lambert = Math.max(0, nx * lx + ny * ly + nz * lz);
                float light = (float) Math.min(1.25, 0.03 + Math.pow(lambert, 1.4) * 1.3);
                int lit = ARGB.scaleRGB(base, Math.min(1.0F, light));
                if (light > 0.85F) lit = lerpRgb((light - 0.85F) / 0.4F * 0.55F, lit, 0xFFE6B4); // тёплый засвет
                // Солнечный блик на воде.
                if (ocean && cl < 0.5) {
                    double spec = Math.pow(Math.max(0, nx * hx + ny * hy + nz * hz), 120) * (1 - cl * 2);
                    lit = lerpRgb((float) Math.min(1, spec * 1.4), lit, 0xFFF4DA);
                }
                // Атмосфера: голубая дымка к краю, на ночной стороне — тонкий синий ободок.
                float rim = (float) Math.pow(1 - nz, 2.4);
                lit = lerpRgb(rim * (0.35F + 0.65F * Math.min(1, light)), lit, 0x6FC8FF);
                img.setPixel(x, y, ARGB.color(1.0F, lit));
            }
        }
        return earthViewTex = register("earth_view", img);
    }
    // ---------- Красивые текстуры сцены (рисуются кодом один раз) ----------

    /** Квадрат с текстурой к камере (камера в начале координат), повёрнут на rot радиан вокруг луча зрения. */
    private static void texBillboard(List<float[]> out, Vec3 c, float size, int rgb, float rot) {
        Vec3 toCam = c.scale(-1).normalize();
        Vec3 u = toCam.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1.0E-6) u = new Vec3(1, 0, 0);
        u = u.normalize();
        Vec3 v = u.cross(toCam).normalize();
        double cs = Math.cos(rot), sn = Math.sin(rot);
        Vec3 a = u.scale(cs).add(v.scale(sn)).scale(size / 2), bb = v.scale(cs).subtract(u.scale(sn)).scale(size / 2);
        addQuad(out, c.subtract(a).add(bb), c.add(a).add(bb), c.add(a).subtract(bb), c.subtract(a).subtract(bb), rgb);
    }

    /** Текстурированные квадраты (uv углов 0..1), полная яркость. */
    private static void emitTextured(com.mojang.blaze3d.vertex.PoseStack.Pose pose, com.mojang.blaze3d.vertex.VertexConsumer b, List<float[]> quads) {
        float[][] uv = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] q : quads) {
            int col = Float.floatToRawIntBits(q[12]);
            for (int k = 0; k < 4; k++) {
                b.addVertex(pose, q[k * 3], q[k * 3 + 1], q[k * 3 + 2]).setColor(col).setUv(uv[k][0], uv[k][1])
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY).setLight(FULL_BRIGHT).setNormal(pose, 0, 1, 0);
            }
        }
    }

    private static double wrap(double v, double lo, double hi) {
        double span = hi - lo;
        return lo + ((v - lo) % span + span) % span;
    }

    private static Identifier softDotTex, starSpikesTex, softRingTex, nebulaTex, milkyTex, regolithTex;

    private static Identifier register(String name, NativeImage img) {
        Identifier id = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/showdown_" + name);
        Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "sandevistan " + name, img));
        return id;
    }

    /** Пиксель «светом»: цвет уже умножен на яркость (так он складывается со светом без тёмных краёв). */
    private static int light(float v, int rgb) {
        v = Mth.clamp(v, 0, 1);
        return ARGB.color(v, ARGB.scaleRGB(rgb, v));
    }

    /** Мягкая круглая точка (гаусс) — звёзды, пыль, свечение солнца. */
    private static Identifier softDot() {
        if (softDotTex != null) return softDotTex;
        int n = 64;
        NativeImage img = new NativeImage(n, n, true);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1, d2 = dx * dx + dy * dy;
            double v = Math.exp(-d2 * 9) + 0.25 * Math.exp(-d2 * 2.2) * (1 - Math.min(1, d2));
            img.setPixel(x, y, light((float) v, 0xFFFFFF));
        }
        return softDotTex = register("soft", img);
    }

    /** Звезда с дифракционными лучами (4 длинных + 4 коротких) и ярким ядром. */
    private static Identifier starSpikes() {
        if (starSpikesTex != null) return starSpikesTex;
        int n = 256;
        NativeImage img = new NativeImage(n, n, true);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1, r = Math.sqrt(dx * dx + dy * dy);
            double core = Math.exp(-r * r * 160) + 0.35 * Math.exp(-r * r * 18);
            double ang = Math.atan2(dy, dx);
            double spikes = 0;
            for (int k = 0; k < 4; k++) {
                double a = ang - k * Math.PI / 2;
                double off = Math.abs(Math.sin(a)) * r; // расстояние до луча
                if (Math.cos(a) > 0) spikes += Math.exp(-off * off * 9000) * Math.pow(1 - Math.min(1, r), 2.2);
                double a2 = ang - Math.PI / 4 - k * Math.PI / 2;
                double off2 = Math.abs(Math.sin(a2)) * r;
                if (Math.cos(a2) > 0) spikes += 0.35 * Math.exp(-off2 * off2 * 14000) * Math.pow(1 - Math.min(1, r * 1.8), 2);
            }
            img.setPixel(x, y, light((float) Math.min(1, core + spikes), 0xFFFFFF));
        }
        return starSpikesTex = register("spikes", img);
    }

    /** Тонкое мягкое кольцо — «зайчики» объектива. */
    private static Identifier softRing() {
        if (softRingTex != null) return softRingTex;
        int n = 128;
        NativeImage img = new NativeImage(n, n, true);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1, r = Math.sqrt(dx * dx + dy * dy);
            double v = Math.exp(-Math.pow((r - 0.78) / 0.07, 2)) + 0.18 * Math.max(0, 1 - r / 0.8);
            img.setPixel(x, y, light((float) (v * (r < 1 ? 1 : 0)), 0xFFFFFF));
        }
        return softRingTex = register("ring", img);
    }

    /** Туманность: клочковатое облако (шум), прозрачное по краям. */
    private static Identifier nebula() {
        if (nebulaTex != null) return nebulaTex;
        int n = 256;
        NativeImage img = new NativeImage(n, n, true);
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double dx = (x + 0.5) / n * 2 - 1, dy = (y + 0.5) / n * 2 - 1, r = Math.sqrt(dx * dx + dy * dy);
            double cloud = fbm(x * 0.022 + 3, y * 0.022 + 9, 6);
            double v = Math.max(0, cloud - 0.38) * 2.2 * Math.max(0, 1 - r) * Math.max(0, 1 - r);
            img.setPixel(x, y, light((float) v, 0xFFFFFF));
        }
        return nebulaTex = register("nebula", img);
    }

    /** Млечный Путь: светящаяся полоса из звёздной дымки, тёмные прожилки пыли, тёплое ядро, россыпь звёзд. */
    private static Identifier milkyWay() {
        if (milkyTex != null) return milkyTex;
        int w = 1024, h = 256;
        NativeImage img = new NativeImage(w, h, true);
        Random r = new Random(3);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            double u = x / (double) w, v = y / (double) h;
            double center = 0.5 + 0.06 * Math.sin(u * 6.2) + 0.03 * Math.sin(u * 17);
            double d = (v - center) / 0.2;
            double edge = Math.min(1, Math.min(u, 1 - u) * 6);
            double haze = Math.exp(-d * d * 2.2) * (0.55 + 0.45 * fbm(u * 18, v * 5, 6)) * edge;
            double lane = Math.exp(-Math.pow((v - center - 0.02) / 0.05, 2)) * Math.max(0, fbm(u * 30 + 7, v * 9, 5) - 0.35) * 2.4;
            haze = Math.max(0, haze - lane * 0.8);
            double core = Math.exp(-Math.pow((u - 0.58) / 0.12, 2)) * Math.exp(-d * d * 3);
            int col = lerpRgb((float) Mth.clamp(core * 1.4, 0, 1), lerpRgb((float) fbm(u * 6, v * 3, 3), 0x7E8CFF, 0xC0A0FF), 0xFFD8A8);
            float val = (float) (haze * 0.55 + core * 0.35);
            if (r.nextInt(220) == 0) val = Math.max(val, 0.7F + r.nextFloat() * 0.3F) * (float) Math.max(0.2, edge);
            img.setPixel(x, y, light(val, col));
        }
        return milkyTex = register("milky", img);
    }

    /** Лунный реголит: пыльно-серый, мелкое зерно, камешки, маленькие кратеры со светлым краем к солнцу. */
    private static Identifier regolith() {
        if (regolithTex != null) return regolithTex;
        int n = 256;
        NativeImage img = new NativeImage(n, n, true);
        Random r = new Random(21);
        double[][] cr = new double[22][];
        for (int i = 0; i < cr.length; i++) cr[i] = new double[]{r.nextDouble() * n, r.nextDouble() * n, 3 + Math.pow(r.nextDouble(), 2) * 22};
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
            double base = 0.66 + 0.16 * (fbm(x * 0.03, y * 0.03, 5) - 0.5) + 0.07 * (fbm(x * 0.2 + 40, y * 0.2, 2) - 0.5);
            base += (r.nextDouble() - 0.5) * 0.08; // зерно пыли
            for (double[] c : cr) {
                double dx = x - c[0], dy = y - c[1];
                // Плитка бесшовная — расстояние «по кругу».
                dx -= Math.round(dx / n) * n;
                dy -= Math.round(dy / n) * n;
                double d = Math.sqrt(dx * dx + dy * dy) / c[2];
                if (d < 1) base *= 0.78 + 0.22 * d - (dy > 0 ? 0.06 * (1 - d) : 0); // чаша, тень у дальнего края
                else if (d < 1.25) base *= 1.0 + 0.16 * (dy > 0 ? 1 : 0.4) * (1.25 - d) / 0.25; // светлый бортик к солнцу
            }
            if (r.nextInt(90) == 0) base *= 0.6; // тёмный камешек
            if (r.nextInt(140) == 0) base *= 1.25; // блестящая крупинка
            int g = Mth.clamp((int) Math.round(base * 255), 0, 255);
            img.setPixel(x, y, ARGB.color(255, g, g, Mth.clamp(g + 4, 0, 255)));
        }
        return regolithTex = register("regolith", img);
    }
    private static Identifier moonGround() {
        if (moonTex != null) return moonTex;
        NativeImage img = new NativeImage(MOON_W, MOON_H, true);
        Random cr = new Random(5);
        double[][] craters = new double[40][];
        for (int i = 0; i < craters.length; i++) {
            craters[i] = new double[]{cr.nextDouble() * MOON_W, 10 + cr.nextDouble() * (MOON_H - 10), 3 + cr.nextDouble() * 14};
        }
        for (int x = 0; x < MOON_W; x++) {
            // Неровный край горизонта.
            double edge = 4 + 4 * fbm(x * 0.03, 7, 3) * 2;
            for (int y = 0; y < MOON_H; y++) {
                if (y < edge) continue;
                double n = fbm(x * 0.05, y * 0.08, 4);
                float v = (float) (0.62 + 0.3 * n) * (0.82F + 0.18F * (1 - y / (float) MOON_H)); // светлый грунт, как в кадре
                for (double[] c : craters) {
                    double dx = (x - c[0]) / (c[2] * 1.8), dy = (y - c[1]) / c[2];
                    double d = dx * dx + dy * dy;
                    if (d < 1) v *= (float) (d < 0.75 ? 0.78 : 1.18);
                }
                int g = Mth.clamp(Math.round(v * 255), 0, 255);
                img.setPixel(x, y, ARGB.color(255, g, g, Mth.clamp(g + 6, 0, 255)));
            }
        }
        moonTex = Identifier.fromNamespaceAndPath("sandevistan", "dynamic/showdown_moon");
        Minecraft.getInstance().getTextureManager().register(moonTex, new DynamicTexture(() -> "sandevistan moon", img));
        return moonTex;
    }

    private static int lerpRgb(float k, int a, int b) {
        k = Mth.clamp(k, 0, 1);
        int r = Math.round(((a >> 16) & 0xFF) * (1 - k) + ((b >> 16) & 0xFF) * k);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - k) + ((b >> 8) & 0xFF) * k);
        int bl = Math.round((a & 0xFF) * (1 - k) + (b & 0xFF) * k);
        return (r << 16) | (g << 8) | bl;
    }

    private static double fbm(double x, double y, int octaves) {
        double sum = 0, amp = 0.5, norm = 0;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x, y);
            norm += amp;
            x *= 2.03;
            y *= 2.03;
            amp *= 0.5;
        }
        return sum / norm;
    }

    private static double noise(double x, double y) {
        int xi = (int) Math.floor(x), yi = (int) Math.floor(y);
        double tx = x - xi, ty = y - yi;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        double a = hash(xi, yi), b = hash(xi + 1, yi), c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
        return Mth.lerp(ty, Mth.lerp(tx, a, b), Mth.lerp(tx, c, d));
    }

    private static double hash(int x, int y) {
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0;
    }
}
