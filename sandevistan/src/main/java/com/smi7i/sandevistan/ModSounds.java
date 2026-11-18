package com.smi7i.sandevistan;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * Звуки мода (файлы пользователя, переведены в .ogg: assets/sandevistan/sounds/, описание — sounds.json).
 * Где какой играет — client/SandevistanSounds.
 */
public class ModSounds {
    /** Вход в состояние Сандевистана (включение). */
    public static final SoundEvent START = register("start");
    /** Сандевистан закончился сам (через 10 секунд, перегрев). */
    public static final SoundEvent EXIT = register("exit");
    /** Гул во время действия (по кругу). */
    public static final SoundEvent HUM = register("hum");
    /** Начало киберпсихоза: «заглючил процессор». */
    public static final SoundEvent PSYCHOSIS_START = register("psychosis_start");
    /** Во время киберпсихоза: «искажение кадра». */
    public static final SoundEvent PSYCHOSIS_DISTORTION = register("psychosis_distortion");
    /** Костюм-мех (звуки вырезаны из видео пользователя): шаг (4 варианта), хуки, удар об землю, приземление. */
    public static final SoundEvent MECH_STEP = register("mech_step");
    public static final SoundEvent MECH_HIT1 = register("mech_hit1");
    public static final SoundEvent MECH_HIT2 = register("mech_hit2");
    public static final SoundEvent MECH_SLAM = register("mech_slam");
    public static final SoundEvent MECH_LAND = register("mech_land");
    /** Укол из шприцов костюма (зелёная вспышка в видео) и посадка в костюм (начало сборки в видео). */
    public static final SoundEvent MECH_INJECT = register("mech_inject");
    public static final SoundEvent MECH_EQUIP = register("mech_equip");
    /** Рывок костюма (видео пользователя с рывком, 4.0 с). */
    public static final SoundEvent MECH_DASH = register("mech_dash");
    /** Вызов костюма: «входящий звонок» (синтезирован) и голосовое сообщение пользователя (обработано: на полтона ниже, «кибер»). */
    public static final SoundEvent MECH_CALL_RING = register("mech_call_ring");
    public static final SoundEvent MECH_CALL_VOICE = register("mech_call_voice");
    /**
     * Кат-сцена посадки — всё из видео пользователя со сборкой костюма (19.39.57, 2–17 с): два разных стука по броне,
     * завод перед захватом, лязг захвата, гулкая тишина (хвост сборки, приглушённый, с эхом).
     */
    public static final SoundEvent MECH_KNOCK = register("mech_knock");
    public static final SoundEvent MECH_KNOCK2 = register("mech_knock2");
    public static final SoundEvent MECH_GRAB_WINDUP = register("mech_grab_windup");
    public static final SoundEvent MECH_GRAB = register("mech_grab");
    /** Способность «вихрь»: весь звук из видео пользователя (23.54.42, с 3.33 с; взрыв — через 3.6 с). */
    public static final SoundEvent MECH_VORTEX = register("mech_vortex");
    /** «Последний бой»: песня пользователя «I Really Want to Stay at Your House» (Samuel Kim, Lorien) с начала, 34 с — от Z до гибели. */
    public static final SoundEvent MECH_SHOWDOWN = register("mech_showdown");
    /** Продолжение песни после кат-сцены — до гибели. */
    public static final SoundEvent MECH_SHOWDOWN_SONG = register("mech_showdown_song");
    /** «Метеор»: прыжок и исчезновение; появление в воздухе + удар об землю (громкий момент через 1.1 с). */
    /** Кардиомонитор пилота при гибели: редкие сигналы → непрерывный тон. */
    public static final SoundEvent MECH_FLATLINE = register("mech_flatline");
    /** Слои из пака пользователя (Boom Library Superheroes): по 4 варианта, игра берёт случайный. */
    public static final SoundEvent BOOM_PUNCH = register("boom_punch");
    public static final SoundEvent BOOM_COLLISION = register("boom_collision");
    public static final SoundEvent BOOM_WHOOSH = register("boom_whoosh");
    public static final SoundEvent BOOM_MOTION = register("boom_motion");
    public static final SoundEvent BOOM_WIND = register("boom_wind");
    public static final SoundEvent BOOM_THRUSTER = register("boom_thruster");
    public static final SoundEvent BOOM_LAND = register("boom_land");
    /** «Магнит»: дорожка из видео пользователя (притяжение → удар через 2.75 с). */
    public static final SoundEvent MECH_PUSH = register("mech_push");
    public static final SoundEvent BOOM_LANDFALL = register("boom_landfall");
    public static final SoundEvent BOOM_SONIC = register("boom_sonic");
    public static final SoundEvent BOOM_TAKEOFF = register("boom_takeoff");
    public static final SoundEvent BOOM_FLASH = register("boom_flash");
    public static final SoundEvent BOOM_ZAP = register("boom_zap");
    public static final SoundEvent BOOM_SPARKLE = register("boom_sparkle");
    public static final SoundEvent BOOM_ORBITAL = register("boom_orbital");
    /** «Сингулярность»: заряд (молнии), выстрел и слияние, гул чёрной дыры, сверхновая. */
    public static final SoundEvent MECH_SING_CHARGE = register("mech_sing_charge");
    public static final SoundEvent MECH_SING_FIRE = register("mech_sing_fire");
    public static final SoundEvent MECH_HOLE = register("mech_hole");
    public static final SoundEvent MECH_SUPERNOVA = register("mech_supernova");
    public static final SoundEvent MECH_METEOR_LAUNCH = register("mech_meteor_launch");
    public static final SoundEvent MECH_METEOR_SLAM = register("mech_meteor_slam");
    public static final SoundEvent MECH_HOLLOW = register("mech_hollow");

    private static SoundEvent register(String name) {
        Identifier id = Sandevistan.id(name);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** Вызывается из Sandevistan.onInitialize(), чтобы звуки зарегистрировались. */
    public static void init() {
    }
}
