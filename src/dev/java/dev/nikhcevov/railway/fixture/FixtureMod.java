package dev.nikhcevov.railway.fixture;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Dev-only Fabric entrypoint for the {@code /railfixture} acceptance surface.
 * This source set is added only to dev server runs; the production jar never
 * contains it.
 */
public final class FixtureMod implements ModInitializer {
	@Override
	public void onInitialize() {
		if (!FixtureEnvironment.enabled()) {
			return;
		}
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				FixtureCommand.register(dispatcher));
		ServerTickEvents.END_SERVER_TICK.register(FixtureService::tick);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> FixtureService.reset());
	}
}
