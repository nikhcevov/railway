package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.domain.TripPhase;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.persistence.TripRecord;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTypeTest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Crash-recovery contract: interrupted checkpoints are reconciled against the
 * loaded world, only service carts are removed, user carts survive, recovery is
 * idempotent and nothing resumes a trip. Every checkpoint used here names a
 * cart in an entity-ticking plot chunk, so reconciliation never has to wait and
 * can never block the shared GameTest server's other trips.
 */
public final class RecoveryGameTests implements CustomTestMethodInvoker {
	private record Stations(StationId from, StationId to) {
	}

	@GameTest(maxTicks = 200)
	public void presentManagedCartIsDiscardedAndCheckpointRemoved(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = dock(helper);
		ServerPlayer player = mockPlayer(helper, dock);
		Stations stations = stations(railway, helper);
		TripId tripId = TripId.random();
		UUID playerUuid = player.getUUID();
		Minecart[] cart = {null};
		boolean[] arranged = {false};

		helper.onEachTick(() -> {
			if (arranged[0] || !ready(level, dock)) {
				return;
			}
			cart[0] = PlayerActions.spawnServiceCart(level, dock, tripId);
			if (cart[0] == null) {
				throw helper.assertionException("service cart was not spawned");
			}
			railway.state().addTrip(checkpoint(level, tripId, playerUuid, cart[0].getUUID(), dock,
					stations, TripPhase.RUNNING));
			arranged[0] = true;
			railway.recovery().reconcileAll();
			// A second pass must be a no-op: no duplicate cart, no lost cart.
			railway.recovery().reconcileAll();
		});
		helper.succeedWhen(() -> {
			if (!arranged[0]) {
				throw helper.assertionException("recovery fixture was not ready");
			}
			if (!cart[0].isRemoved()) {
				throw helper.assertionException("recovered service cart was not removed");
			}
			requireNoCheckpoint(helper, railway, tripId);
			if (railway.trips().hasActiveTrip(playerUuid)) {
				throw helper.assertionException("recovery resumed a trip");
			}
			if (hasCart(level, near(dock))) {
				throw helper.assertionException("idempotent recovery spawned a duplicate cart");
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void checkpointWithAPassengerOnlyStopsTheCart(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = dock(helper);
		ServerPlayer player = mockPlayer(helper, dock);
		Stations stations = stations(railway, helper);
		TripId tripId = TripId.random();
		Minecart[] cart = {null};
		BlockPos[] riding = {null};
		boolean[] arranged = {false};
		boolean[] dismounted = {false};

		helper.onEachTick(() -> {
			if (!arranged[0]) {
				if (!ready(level, dock)) {
					return;
				}
				cart[0] = PlayerActions.spawnServiceCart(level, dock, tripId);
				if (cart[0] == null || !PlayerActions.mount(player, cart[0])) {
					throw helper.assertionException("passenger could not board the recovery cart");
				}
				// The rider sits above the cart; that baseline is what recovery must
				// never change. Comparing it avoids assuming the riding offset.
				riding[0] = player.blockPosition();
				railway.state().addTrip(checkpoint(level, tripId, player.getUUID(), cart[0].getUUID(), dock,
						stations, TripPhase.RUNNING));
				arranged[0] = true;
				railway.recovery().reconcileAll();
				return;
			}
			if (dismounted[0]) {
				return;
			}
			if (cart[0].isRemoved()) {
				throw helper.assertionException("occupied cart was removed with its passenger");
			}
			if (player.getVehicle() != cart[0]) {
				throw helper.assertionException("recovery dismounted the passenger");
			}
			if (cart[0].getDeltaMovement().lengthSqr() != 0.0) {
				throw helper.assertionException("recovery did not stop the occupied cart");
			}
			if (!player.blockPosition().equals(riding[0])) {
				throw helper.assertionException("recovery moved the passenger from "
						+ riding[0].toShortString() + " to " + player.blockPosition().toShortString());
			}
			// Leaving the cart is the passenger's own decision; afterwards recovery
			// must clean the cart and the checkpoint without any teleport.
			player.stopRiding();
			dismounted[0] = true;
		});
		helper.succeedWhen(() -> {
			if (!dismounted[0]) {
				throw helper.assertionException("passenger was not dismounted by the test");
			}
			if (!cart[0].isRemoved()) {
				throw helper.assertionException("emptied recovery cart was not removed");
			}
			requireNoCheckpoint(helper, railway, tripId);
		});
	}

	@GameTest(maxTicks = 200)
	public void absentCartCheckpointIsDropped(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = dock(helper);
		ServerPlayer player = mockPlayer(helper, dock);
		Stations stations = stations(railway, helper);
		TripId tripId = TripId.random();
		boolean[] arranged = {false};

		helper.onEachTick(() -> {
			if (arranged[0] || !ready(level, dock)) {
				return;
			}
			railway.state().addTrip(checkpoint(level, tripId, player.getUUID(), UUID.randomUUID(), dock,
					stations, TripPhase.RUNNING));
			arranged[0] = true;
			railway.recovery().reconcileAll();
		});
		helper.succeedWhen(() -> {
			if (!arranged[0]) {
				throw helper.assertionException("recovery fixture was not ready");
			}
			requireNoCheckpoint(helper, railway, tripId);
			if (hasCart(level, helper.absoluteAABB(helper.getBounds().inflate(2.0)))) {
				throw helper.assertionException("absent-cart reconciliation spawned a cart");
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void positionlessCheckpointIsDropped(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = dock(helper);
		ServerPlayer player = mockPlayer(helper, dock);
		Stations stations = stations(railway, helper);
		TripId tripId = TripId.random();
		boolean[] arranged = {false};

		helper.onEachTick(() -> {
			if (arranged[0] || !ready(level, dock)) {
				return;
			}
			railway.state().addTrip(new TripRecord(tripId, player.getUUID(), UUID.randomUUID(),
					stations.from(), stations.to(), TripPhase.RUNNING, level.getServer().getTickCount()));
			arranged[0] = true;
			railway.recovery().reconcileAll();
		});
		helper.succeedWhen(() -> {
			if (!arranged[0]) {
				throw helper.assertionException("recovery fixture was not ready");
			}
			requireNoCheckpoint(helper, railway, tripId);
		});
	}

	@GameTest(maxTicks = 200)
	public void unmanagedCartAtCheckpointUuidIsPreserved(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = dock(helper);
		ServerPlayer player = mockPlayer(helper, dock);
		Stations stations = stations(railway, helper);
		TripId tripId = TripId.random();
		Minecart[] userCart = {null};
		boolean[] arranged = {false};

		helper.onEachTick(() -> {
			if (arranged[0] || !ready(level, dock)) {
				return;
			}
			userCart[0] = spawnUserCart(level, dock);
			if (userCart[0] == null) {
				throw helper.assertionException("user cart was not spawned");
			}
			railway.state().addTrip(checkpoint(level, tripId, player.getUUID(), userCart[0].getUUID(), dock,
					stations, TripPhase.RUNNING));
			arranged[0] = true;
			railway.recovery().reconcileAll();
		});
		helper.succeedWhen(() -> {
			if (!arranged[0]) {
				throw helper.assertionException("recovery fixture was not ready");
			}
			if (userCart[0].isRemoved()) {
				throw helper.assertionException("recovery removed a user cart");
			}
			requireNoCheckpoint(helper, railway, tripId);
			userCart[0].discard();
		});
	}

	@GameTest(maxTicks = 200)
	public void lateChunkReadyRemovesOnlyOrphanServiceCarts(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = dock(helper);
		mockPlayer(helper, dock);
		Minecart[] orphan = {null};
		Minecart[] userCart = {null};
		boolean[] arranged = {false};

		helper.onEachTick(() -> {
			if (arranged[0] || !ready(level, dock)) {
				return;
			}
			orphan[0] = PlayerActions.spawnServiceCart(level, dock, TripId.random());
			userCart[0] = spawnUserCart(level, new BlockLocation(dock.x(), dock.y(), dock.z() + 1));
			if (orphan[0] == null || userCart[0] == null) {
				throw helper.assertionException("late-load fixture carts were not spawned");
			}
			arranged[0] = true;
			LevelChunk chunk = level.getChunkAt(RailWorld.pos(dock));
			ServerChunkEvents.FULL_CHUNK_STATUS_CHANGE.invoker().onFullChunkStatusChange(
					level, chunk, FullChunkStatus.BLOCK_TICKING, FullChunkStatus.ENTITY_TICKING);
		});
		helper.succeedWhen(() -> {
			if (!arranged[0]) {
				throw helper.assertionException("late-load fixture was not ready");
			}
			if (!orphan[0].isRemoved()) {
				throw helper.assertionException("orphan service cart survived its chunk becoming ready");
			}
			if (userCart[0].isRemoved()) {
				throw helper.assertionException("late-chunk cleanup removed a user cart");
			}
			userCart[0].discard();
		});
	}

	@GameTest(maxTicks = 200)
	public void joinStopsARecoveredCartInPlace(GameTestHelper helper) {
		Railway railway = Railway.get();
		ServerLevel level = helper.getLevel();
		BlockLocation dock = dock(helper);
		ServerPlayer player = mockPlayer(helper, dock);
		Stations stations = stations(railway, helper);
		TripId tripId = TripId.random();
		Minecart[] cart = {null};
		BlockPos[] riding = {null};
		boolean[] arranged = {false};

		helper.onEachTick(() -> {
			if (arranged[0] || !ready(level, dock)) {
				return;
			}
			cart[0] = PlayerActions.spawnServiceCart(level, dock, tripId);
			if (cart[0] == null || !PlayerActions.mount(player, cart[0])) {
				throw helper.assertionException("join fixture could not mount the passenger");
			}
			riding[0] = player.blockPosition();
			// The checkpoint keeps a parallel late-chunk pass from treating this
			// cart as an orphan while the join path is being tested.
			railway.state().addTrip(checkpoint(level, tripId, player.getUUID(), cart[0].getUUID(), dock,
					stations, TripPhase.RUNNING));
			cart[0].setDeltaMovement(0.5, 0.0, 0.0);
			arranged[0] = true;
			railway.recovery().onPlayerJoin(player);
		});
		helper.succeedWhen(() -> {
			if (!arranged[0]) {
				throw helper.assertionException("join fixture was not ready");
			}
			if (player.getVehicle() != cart[0]) {
				throw helper.assertionException("join handling ejected the passenger");
			}
			if (cart[0].getDeltaMovement().lengthSqr() != 0.0) {
				throw helper.assertionException("join handling did not stop the cart");
			}
			if (!player.blockPosition().equals(riding[0])) {
				throw helper.assertionException("join handling moved the passenger from "
						+ riding[0].toShortString() + " to " + player.blockPosition().toShortString());
			}
			player.stopRiding();
			cart[0].discard();
			railway.state().removeTrip(tripId);
		});
	}

	// ---------------------------------------------------------------- helpers

	/** A grounded dock: carts must not fall while a recovery assertion runs. */
	private static BlockLocation dock(GameTestHelper helper) {
		helper.setBlock(new BlockPos(0, 0, 0), Blocks.STONE);
		return RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0)));
	}

	private static ServerPlayer mockPlayer(GameTestHelper helper, BlockLocation dock) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(dock.x() + 0.5, dock.y(), dock.z() + 0.5);
		return player;
	}

	/** The checkpoint chunk must be entity-ticking before recovery looks at it. */
	private static boolean ready(ServerLevel level, BlockLocation position) {
		ChunkPos pos = ChunkPos.containing(RailWorld.pos(position));
		return level.getChunkSource().hasChunk(pos.x(), pos.z())
				&& level.areEntitiesActuallyLoadedAndTicking(pos);
	}

	private static TripRecord checkpoint(ServerLevel level, TripId tripId, UUID playerUuid, UUID cartUuid,
			BlockLocation position, Stations stations, TripPhase phase) {
		return new TripRecord(tripId, playerUuid, cartUuid, stations.from(), stations.to(), phase,
				level.getServer().getTickCount(), RailWorld.dimension(level), position);
	}

	private static Stations stations(Railway railway, GameTestHelper helper) {
		String unique = Long.toString(System.nanoTime() & 0xFFFFFFL, 36);
		String dimension = RailWorld.dimension(helper.getLevel());
		BlockLocation dock = dock(helper);
		StationId from = new StationId("recfrom" + unique);
		StationId to = new StationId("recto" + unique);
		railway.state().addStation(new Station(from, "Recovery From " + unique, dimension, dock,
				dock.offset(0, 0, 1), 0.0F, false, railway.state().nextRevision()));
		railway.state().addStation(new Station(to, "Recovery To " + unique, dimension,
				dock.offset(0, 0, 2), dock.offset(0, 0, 3), 0.0F, false,
				railway.state().nextRevision()));
		return new Stations(from, to);
	}

	private static Minecart spawnUserCart(ServerLevel level, BlockLocation position) {
		Minecart cart = EntityType.MINECART.create(level, EntitySpawnReason.COMMAND);
		if (cart == null) {
			return null;
		}
		cart.setPos(position.x() + 0.5, position.y(), position.z() + 0.5);
		return level.addFreshEntity(cart) ? cart : null;
	}

	private static void requireNoCheckpoint(GameTestHelper helper, Railway railway, TripId tripId) {
		if (railway.state().trip(tripId).isPresent()) {
			throw helper.assertionException("recovery checkpoint " + tripId + " was not removed");
		}
	}

	private static net.minecraft.world.phys.AABB near(BlockLocation position) {
		return new net.minecraft.world.phys.AABB(position.x() - 1.0, position.y() - 1.0, position.z() - 1.0,
				position.x() + 2.0, position.y() + 2.0, position.z() + 2.0);
	}

	private static boolean hasCart(ServerLevel level, net.minecraft.world.phys.AABB area) {
		EntityTypeTest<Entity, Minecart> carts = EntityTypeTest.forClass(Minecart.class);
		List<Minecart> found = level.getEntities(carts, area,
				cart -> cart.entityTags().contains(PlayerActions.MANAGED_TAG));
		return !found.isEmpty();
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
