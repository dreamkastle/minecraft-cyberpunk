package com.smi7i.sandevistan.client;

/**
 * Цвет темы интерфейса (выбирается в настройках). Меняет панели, меню, слоты имплантов, страницу имплантов
 * и список нейро интерфейса. Шкалы Сандевистана, фильтр экрана, послеобразы и эффекты не трогает.
 * Тёмные темы чуть приглушены, чтобы не слепить.
 */
public enum HoloTheme {
    BASE("Базовая", 0x2AE0C0, 0x39FF6A),
    RED("Тёмно-красная неон", 0xC4283C, 0xE5495B),
    GREEN("Тёмно-зелёная неон", 0x24A84C, 0x45D472);

    public final String label;
    /** Основной цвет рамок, панелей и текста. */
    public final int accent;
    /** Яркий цвет для «заполнено/активно» (слот с имплантом). */
    public final int glow;

    HoloTheme(String label, int accent, int glow) {
        this.label = label;
        this.accent = accent;
        this.glow = glow;
    }

    public static HoloTheme current() {
        try {
            return valueOf(SandevistanConfig.get().theme);
        } catch (Exception e) {
            return BASE;
        }
    }

    public static int accent() {
        return current().accent;
    }

    public static int glow() {
        return current().glow;
    }

    public HoloTheme next() {
        HoloTheme[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
