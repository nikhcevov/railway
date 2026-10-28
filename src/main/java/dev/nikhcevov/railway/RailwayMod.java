package dev.nikhcevov.railway;

import dev.nikhcevov.railway.commands.Messages;
import dev.nikhcevov.railway.commands.RailCommand;
import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.fixture.FixtureCommand;
import dev.nikhcevov.railway.fixture.FixtureEnvironment;
import dev.nikhcevov.railway.fixture.FixtureService;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.TripService;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RailwayMod implements ModInitializer {
	public static final String MOD_ID = "railway";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static final Map<UUID, Integer> LAST_PRESS_TICK = new HashMap<>();
	/** Marker verification cadence; low frequency keeps the check off the hot path. */
	private static final int MARKER_VERIFY_INTERVAL_TICKS = 40;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				RailCommand.register(dispatcher));
		ServerLifecycleEvents.SERVER_STARTED.register(Railway::start);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> Railway.stop());
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!Railway.isStarted()) {
				return;
			}
			Railway railway = Railway.get();
			if (railway.disabled()) {
				return;
			}
			railway.recovery().tick(server);
			railway.scans().tick(server);
			railway.trips().tick(server);
			if (server.getTickCount() % MARKER_VERIFY_INTERVAL_TICKS == 0) {
				railway.signs().verifyMarkers(server);
			}
		});
		// Dev-only acceptance fixtures. The package and this single block are
		// deleted before a release; production jars never register the command.
		if (FixtureEnvironment.enabled()) {
			CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
					FixtureCommand.register(dispatcher));
			ServerTickEvents.END_SERVER_TICK.register(FixtureService::tick);
			ServerLifecycleEvents.SERVER_STOPPING.register(server -> FixtureService.reset());
		}
		// Entity-ticking is the first moment a recovered chunk can be trusted to
		// contain (or not contain) the checkpointed cart.
		ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.register((level, chunk, oldStatus, newStatus) -> {
			if (newStatus == FullChunkStatus.ENTITY_TICKING && Railway.isStarted()) {
				Railway.get().recovery().onChunkReady(level, chunk);
			}
		});
		UseBlockCallback.EVENT.register(RailwayMod::onUseBlock);
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (level instanceof net.minecraft.server.level.ServerLevel serverLevel && Railway.isStarted()) {
				Railway railway = Railway.get();
				railway.scans().invalidate(RailWorld.loc(pos));
				railway.signs().onBlockBreak(serverLevel, pos, state, blockEntity);
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (Railway.isStarted() && handler.player != null) {
				Railway.get().recovery().onPlayerJoin(handler.player);
			}
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (Railway.isStarted() && handler.player != null) {
				Railway.get().trips().playerLeft(handler.player.getUUID());
			}
		});
	}

	private static InteractionResult onUseBlock(net.minecraft.world.entity.player.Player player, Level level,
			InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.PASS;
		}
		if (!Railway.isStarted()) {
			return InteractionResult.PASS;
		}
		Railway railway = Railway.get();
		if (railway.disabled()) {
			return InteractionResult.PASS;
		}
		Binding binding = railway.state()
				.binding(RailWorld.dimension(serverPlayer.level()), RailWorld.loc(hit.getBlockPos()))
				.orElse(null);
		if (binding == null) {
			return InteractionResult.PASS;
		}

		int tick = serverPlayer.level().getServer().getTickCount();
		Integer last = LAST_PRESS_TICK.put(serverPlayer.getUUID(), tick);
		if (last != null && last == tick) {
			return InteractionResult.PASS;
		}
		if (LAST_PRESS_TICK.size() > 256) {
			LAST_PRESS_TICK.entrySet().removeIf(entry -> tick - entry.getValue() > 20);
		}

		TripService.StartResult result = railway.trips().start(serverPlayer, binding);
		if (result instanceof TripService.StartResult.Rejected rejected) {
			serverPlayer.sendOverlayMessage(Messages.failure(serverPlayer, rejected.failure()));
		}
		return InteractionResult.PASS;
	}
}
