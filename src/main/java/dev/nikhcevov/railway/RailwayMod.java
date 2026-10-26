package dev.nikhcevov.railway;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RailwayMod implements ModInitializer {
	public static final String MOD_ID = "railway";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Railway initializing");
	}
}
