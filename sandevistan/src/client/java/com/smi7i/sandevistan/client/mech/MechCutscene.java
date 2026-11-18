package com.smi7i.sandevistan.client.mech;

import com.smi7i.sandevistan.ModSounds;
import com.smi7i.sandevistan.client.SandevistanSounds;
import com.smi7i.sandevistan.mech.BoardPayload;
import com.smi7i.sandevistan.mech.MechManager;
import com.smi7i.sandevistan.mech.MechSuitEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Кат-сцена посадки в костюм (по мотивам Roblox): камера от третьего лица — низко и по диагонали, медленно плывёт;
 * персонаж сам подходит к костюму и дважды аккуратно стучит по броне (стук — сервер), костюм затягивает его —
 * экран чернеет и всё стихает (гулкая тишина), потом чёрный экран раскрывается на две половины — мы уже в костюме.
 * Тайминг общий с сервером: MechManager.BOARD_*.
 */
public final class MechCutscene {
    // ===== Настройки камеры =====
    /** Камера: насколько впереди костюма, вбок (по диагонали), над землёй (низко — снизу вверх) — в блоках. */
    private static final double CAM_FRONT = 5.6, CAM_SIDE = 5.0, CAM_HEIGHT = 0.3;
    /** За всю сцену камера медленно облетает костюм на столько градусов и чуть приближается. */
    private static final float CAM_ORBIT = 18.0F, CAM_DOLLY = 1.4F;
    /** Угол обзора камеры сцены (узкий — «киношный», длиннофокусный объектив). */
    public static final float CINE_FOV = 36.0F;
    /** Покачивание камеры «с рук»: сдвиг (блоки) и поворот (градусы). */
    private static final float SWAY_POS = 0.045F, SWAY_ROT = 0.7F;
    /** Куда смотрит: между игроком и костюмом, на такой высоте. */
    private static final double LOOK_HEIGHT = 1.45;
    /** Где игрок останавливается перед костюмом (блоки от его центра). */
    private static final double STAND_DIST = 1.2; // вплотную к костюму — видно, что стучит по броне
    /** Полосы «кино» сверху и снизу (доля высоты экрана). */
    private static final float LETTERBOX = 0.11F;
    /** За сколько тиков до захвата экран начинает краснеть. */
    private static final float RED_TICKS = 30;

    private static boolean active;
    private static int suitId;
    private static long start;
    private static Vec3 suitPos = Vec3.ZERO;
    private static float suitYaw;
    private static CameraType previousCamera;
    private static boolean pulled, revealed, struck;

    private MechCutscene() {}

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(BoardPayload.TYPE, (payload, context) -> begin(payload.suitId(), payload.start()));
        ClientTickEvents.START_CLIENT_TICK.register(MechCutscene::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((h, c) -> active = false);
    }

    public static boolean active() {
        return active;
    }

    /** Время сцены в тиках (с долей тика). */
    private static float age(float partial) {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : mc.level.getGameTime() - start + partial;
    }

    private static void begin(int id, long startTime) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(id) instanceof MechSuitEntity suit)) return;
        active = true;
        suitId = id;
        start = startTime;
        suitPos = suit.position();
        suitYaw = suit.getYRot();
        pulled = revealed = struck = false;
        previousCamera = mc.options.getCameraType();
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); // чтобы игра рисовала своего персонажа
    }

    private static void end() {
        Minecraft mc = Minecraft.getInstance();
        if (!pulled && previousCamera != null) mc.options.setCameraType(previousCamera);
        active = false;
    }

    /** Перед костюмом (куда он смотрит). */
    private static Vec3 front() {
        return Vec3.directionFromRotation(0, suitYaw);
    }

    private static void tick(Minecraft mc) {
        if (!active) return;
        var player = mc.player;
        if (player == null || mc.level == null || !player.isAlive()) {
            end();
            return;
        }
        float age = age(0);
        Entity suit = mc.level.getEntity(suitId);
        if (!pulled && age < MechManager.BOARD_PULL - 1 && !(suit instanceof MechSuitEntity)) {
            end(); // костюм пропал (взорвали) — сцена отменяется
            return;
        }
        if (!pulled) {
            // Подходит к костюму и поворачивается к нему лицом.
            Vec3 stand = suitPos.add(front().scale(STAND_DIST));
            double dx = stand.x - player.getX(), dz = stand.z - player.getZ();
            boolean walking = age < MechManager.BOARD_WALK && dx * dx + dz * dz > 0.3 * 0.3;
            Vec3 look = walking ? stand : suitPos;
            float wantYaw = (float) Math.toDegrees(Math.atan2(-(look.x - player.getX()), look.z - player.getZ()));
            float yaw = player.getYRot() + Mth.clamp(Mth.wrapDegrees(wantYaw - player.getYRot()), -20, 20);
            player.setYRot(yaw);
            player.setYHeadRot(yaw);
            player.setXRot(Mth.lerp(0.2F, player.getXRot(), walking ? 5.0F : 18.0F)); // смотрит на броню
        }
        if (!struck && age >= MechManager.BOARD_PULL - 1) {
            struck = true;
            MechShake.kick(3.0F); // костюм резко бьёт — камеру встряхивает, и экран сразу чернеет
        }
        if (!pulled && age >= MechManager.BOARD_PULL) {
            // Затянуло: тишина, камера — снова как была (обычно от первого лица, уже в костюме).
            pulled = true;
            mc.getSoundManager().stop();
            SandevistanSounds.play(mc, ModSounds.MECH_HOLLOW, 1.0F);
            if (previousCamera != null) mc.options.setCameraType(previousCamera);
        }
        if (!revealed && age >= MechManager.BOARD_REVEAL) {
            revealed = true;
            SandevistanSounds.play(mc, ModSounds.MECH_EQUIP, 0.9F);
        }
        if (age >= MechManager.BOARD_END) end();
    }

    /** Скорость шага в сцене (0..1): плавно трогается, спокойно идёт, мягко тормозит у костюма. */
    public static float forwardAmount() {
        Minecraft mc = Minecraft.getInstance();
        if (!active || pulled || mc.player == null) return 0;
        Vec3 stand = suitPos.add(front().scale(STAND_DIST));
        double dist = Math.sqrt(Math.pow(stand.x - mc.player.getX(), 2) + Math.pow(stand.z - mc.player.getZ(), 2));
        float age = age(0);
        if (age >= MechManager.BOARD_WALK || dist < 0.3) return 0;
        float startUp = smooth(age / 8.0F);
        float slowDown = (float) Mth.clamp(dist / 1.4, 0.3, 1.0);
        return 0.72F * startUp * slowDown;
    }

    /** Ввод игрока в сцене: идёт вперёд, пока не дошёл; остальное время — стоит. null — сцены нет. */
    public static Input forcedInput() {
        if (!active) return null;
        Minecraft mc = Minecraft.getInstance();
        if (pulled || mc.player == null) return new Input(false, false, false, false, false, false, false);
        Vec3 stand = suitPos.add(front().scale(STAND_DIST));
        double dx = stand.x - mc.player.getX(), dz = stand.z - mc.player.getZ();
        boolean walking = age(0) < MechManager.BOARD_WALK && dx * dx + dz * dz > 0.3 * 0.3;
        return new Input(walking, false, false, false, false, false, false);
    }

    /** Камера сцены (до затягивания): null — обычная камера. Возвращает {x, y, z, yaw, pitch}. */
    public static double[] camera(float partial) {
        if (!active || pulled) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        float t = Mth.clamp(age(partial) / MechManager.BOARD_PULL, 0, 1);
        float ease = t * t * (3 - 2 * t);
        Vec3 f = front();
        Vec3 r = new Vec3(-f.z, 0, f.x);
        // Медленный облёт и наезд: смещение камеры поворачивается вокруг костюма.
        double ang = Math.toRadians(-CAM_ORBIT / 2 + CAM_ORBIT * ease);
        double scale = 1.0 - CAM_DOLLY * ease / Math.hypot(CAM_FRONT, CAM_SIDE);
        Vec3 off = f.scale(CAM_FRONT).add(r.scale(CAM_SIDE));
        off = new Vec3(off.x * Math.cos(ang) - off.z * Math.sin(ang), 0, off.x * Math.sin(ang) + off.z * Math.cos(ang)).scale(scale);
        Vec3 playerPos = mc.player.getPosition(partial);
        Vec3 target = playerPos.lerp(suitPos, 0.5).add(0, LOOK_HEIGHT, 0);
        Vec3 cam = suitPos.add(off).add(0, CAM_HEIGHT + 0.25 * ease, 0);
        // Камера не должна оказаться в стене: подтягиваем ближе к цели.
        HitResult hit = mc.level.clip(new ClipContext(target, cam, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() != HitResult.Type.MISS) cam = hit.getLocation().add(target.subtract(hit.getLocation()).normalize().scale(0.25));
        Vec3 d = target.subtract(cam);
        double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
        double pitch = -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        // Камера «с рук»: медленное неровное покачивание (несколько синусов разной частоты).
        double s = net.minecraft.util.Util.getMillis() / 1000.0;
        cam = cam.add(SWAY_POS * (Math.sin(s * 0.9) + 0.4 * Math.sin(s * 2.3)), SWAY_POS * 0.8 * Math.sin(s * 1.3 + 1.0),
                SWAY_POS * (Math.cos(s * 0.7) + 0.4 * Math.sin(s * 1.9 + 2.0)));
        yaw += SWAY_ROT * (Math.sin(s * 0.6) + 0.3 * Math.sin(s * 1.7));
        pitch += SWAY_ROT * 0.6 * Math.sin(s * 0.8 + 1.0);
        return new double[]{cam.x, cam.y, cam.z, yaw, pitch};
    }

    /** Угол обзора сцены (пока кинокамера), иначе null. */
    public static Float fov() {
        return active && !pulled ? CINE_FOV : null;
    }

    // ===== Анимация стука =====
    /** Рука поднимается к броне за столько тиков до первого стука. */
    private static final float RAISE_BEFORE = 12;

    /** Идёт ли сейчас поза стука (тогда EMF / Fresh Animations не трогают модель). */
    public static boolean knockPhase(float t) {
        return t >= MechManager.BOARD_KNOCK1 - RAISE_BEFORE && t < MechManager.BOARD_PULL + 2;
    }

    /**
     * Поза стука (модель игрока): правая рука плавно поднимается к груди костюма, перед каждым стуком
     * костяшки отводятся вверх (замах) и резко опускаются на броню, с маленькой отдачей; левая рука расслаблена,
     * голова смотрит на руку. После стуков рука немного опускается и ждёт.
     */
    public static void knockPose(net.minecraft.client.model.player.PlayerModel m, float t) {
        float raise = smooth((t - (MechManager.BOARD_KNOCK1 - RAISE_BEFORE)) / 8.0F);
        float lower = smooth((t - (MechManager.BOARD_KNOCK2 + 5)) / 8.0F) * 0.3F;
        float k = raise * (1 - lower);
        if (k <= 0) return;
        float knock = knockOffset(t - MechManager.BOARD_KNOCK1) + knockOffset(t - MechManager.BOARD_KNOCK2);
        m.rightArm.xRot = Mth.lerp(k, m.rightArm.xRot, -1.42F + knock);
        m.rightArm.yRot = Mth.lerp(k, m.rightArm.yRot, -0.2F);
        m.rightArm.zRot = Mth.lerp(k, m.rightArm.zRot, 0.06F);
        m.leftArm.xRot = Mth.lerp(k, m.leftArm.xRot, 0.12F);
        m.leftArm.yRot = Mth.lerp(k, m.leftArm.yRot, 0.0F);
        m.leftArm.zRot = Mth.lerp(k, m.leftArm.zRot, -0.1F);
        m.head.xRot = Mth.lerp(k * 0.6F, m.head.xRot, -0.1F);
        m.head.yRot = Mth.lerp(k * 0.4F, m.head.yRot, -0.12F);
    }

    /** Один стук: d — тики от удара. Замах вверх (-) за 5 тиков, резкий удар, маленькая отдача. */
    private static float knockOffset(float d) {
        if (d < -5 || d > 4) return 0;
        if (d < 0) {
            float u = (d + 5) / 5.0F;              // 0..1
            return -0.4F * (float) Math.sin(u * Math.PI * 0.5) * (u < 0.8F ? 1 : (1 - u) / 0.2F); // плавно вверх, у удара — вниз
        }
        return 0.07F * (float) Math.sin(d / 4.0F * Math.PI) * (1 - d / 4.0F); // отдача от брони
    }

    /** Насколько глушить звуки мира (0..1): в чёрном экране — почти полная тишина. */
    public static float silence() {
        if (!active || !pulled) return 0;
        float age = age(0);
        if (age < MechManager.BOARD_REVEAL) return 1;
        return 1 - Mth.clamp((age - MechManager.BOARD_REVEAL) / (MechManager.BOARD_END - MechManager.BOARD_REVEAL), 0, 1);
    }

    /** Поверх всего интерфейса: полосы «кино», затемнение, чёрный экран и его раскрытие на две половины. */
    public static void render(GuiGraphicsExtractor g, float partial) {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getGuiScaledWidth(), h = mc.getWindow().getGuiScaledHeight();
        float age = age(partial);
        int black = 0xFF000000;
        if (age < MechManager.BOARD_PULL) {
            // Перед захватом экран потихоньку краснеет (гуще по краям), с лёгкой пульсацией.
            float red = smooth((age - (MechManager.BOARD_PULL - RED_TICKS)) / RED_TICKS);
            if (red > 0) {
                float pulse = 0.85F + 0.15F * Mth.sin(age * 0.7F);
                g.fill(0, 0, w, h, com.smi7i.sandevistan.client.HoloUi.argb(0.38F * red * pulse, 0xB0000A));
                for (int i = 0; i < 8; i++) {
                    float a = 0.09F * red * (8 - i) / 8.0F;
                    int e = Math.round(h * 0.28F * (i + 1) / 8.0F);
                    int c = com.smi7i.sandevistan.client.HoloUi.argb(a, 0xFF1020);
                    g.fill(0, 0, w, e, c);
                    g.fill(0, h - e, w, h, c);
                    g.fill(0, 0, e, h, c);
                    g.fill(w - e, 0, w, h, c);
                }
            }
            float bars = smooth(age / 12.0F) * LETTERBOX * h;
            g.fill(0, 0, w, Math.round(bars), black);
            g.fill(0, h - Math.round(bars), w, h, black);

        } else if (age < MechManager.BOARD_REVEAL) {
            g.fill(0, 0, w, h, black);
        } else {
            // Чёрный экран раскрывается: верх уезжает вверх, низ — вниз; по шву — тонкая зелёная полоса.
            float k = smooth((age - MechManager.BOARD_REVEAL) / (MechManager.BOARD_END - MechManager.BOARD_REVEAL));
            int half = h / 2;
            int shift = Math.round(k * (half + 2));
            g.fill(0, 0, w, half - shift, black);
            g.fill(0, half + shift, w, h, black);
            int line = com.smi7i.sandevistan.client.HoloUi.argb(0.9F * (1 - k), 0x39FF6A);
            if (half - shift > 0) g.fill(0, half - shift - 1, w, half - shift, line);
            if (half + shift < h) g.fill(0, half + shift, w, half + shift + 1, line);
        }
    }

    private static float smooth(float x) {
        x = Mth.clamp(x, 0, 1);
        return x * x * (3 - 2 * x);
    }
}
