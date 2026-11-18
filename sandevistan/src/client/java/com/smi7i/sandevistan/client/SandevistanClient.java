package com.smi7i.sandevistan.client;

import net.fabricmc.api.ClientModInitializer;

public class SandevistanClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ParryClient.init(); // отбивание снарядов ЛКМ/ПКМ с Сандевистаном или в костюме
		// Иммуносупрессор: укол начинается, только если есть ячейка киберпсихоза и нет приступа.
		com.smi7i.sandevistan.ImmunosuppressantItem.clientCanUse =
				() -> ClientSandevistanState.streak > 0 && !ClientSandevistanState.psychosis();
	}
}