package com.smi7i.sandevistan.client;

import net.fabricmc.api.ClientModInitializer;

public class SandevistanClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ParryClient.init(); // отбивание снарядов ЛКМ/ПКМ с Сандевистаном или в костюме
	}
}