package com.helperbot;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class HelperBotMod implements ModInitializer {
	public static final String MOD_ID = "helperbot";

	@Override
	public void onInitialize() {
		ModEntities.init();
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> HelperCommands.register(dispatcher));
	}
}
