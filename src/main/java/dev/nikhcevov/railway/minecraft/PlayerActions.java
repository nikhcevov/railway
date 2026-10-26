package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.TripId;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.Set;

/** Player and service-cart operations. All calls run on the server thread. */
public final class PlayerActions {
	public static final String MANAGED_TAG = "railway_managed";
	public static final String TRIP_TAG_PREFIX = "railway_trip_";

	private PlayerActions() {
	}

	public static Optional<BlockLocation> targetedBlock(ServerPlayer player, double range) {
		HitResult hit = player.pick(range, 0.0F, false);
		if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult blockHit)) {
			return Optional.empty();
		}
		return Optional.of(RailWorld.loc(blockHit.getBlockPos()));
	}

	public static boolean withinRadius(ServerPlayer player, BlockLocation dock, double radius) {
		double dx = player.getX() - (dock.x() + 0.5);
		double dy = player.getY() - (dock.y() + 0.5);
		double dz = player.getZ() - (dock.z() + 0.5);
		return dx * dx + dy * dy + dz * dz <= radius * radius;
	}

	/**
	 * Boarding envelope for sign-created stations: horizontal 4 blocks and vertical 3
	 * blocks from the dock. It deliberately keeps the full vertical tolerance so a
	 * button at {@code dockY + 3} is reachable.
	 */
	public static boolean withinSignRadius(ServerPlayer player, BlockLocation dock) {
		double dx = player.getX() - (dock.x() + 0.5);
		double dz = player.getZ() - (dock.z() + 0.5);
		double dy = player.getY() - (dock.y() + 0.5);
		return dx * dx + dz * dz <= 4.0 * 4.0 && Math.abs(dy) <= 3.0;
	}

	public static Minecart spawnServiceCart(ServerLevel level, BlockLocation dock, TripId tripId) {
		Minecart cart = EntityType.MINECART.create(level, EntitySpawnReason.COMMAND);
		if (cart == null) {
			return null;
		}
		cart.setPos(dock.x() + 0.5, dock.y(), dock.z() + 0.5);
		cart.setDeltaMovement(Vec3.ZERO);
		cart.addTag(MANAGED_TAG);
		cart.addTag(TRIP_TAG_PREFIX + tripId.value().toString().replace("-", ""));
		if (!level.addFreshEntity(cart)) {
			cart.discard();
			return null;
		}
		return cart;
	}

	public static boolean mount(ServerPlayer player, Minecart cart) {
		return player.startRiding(cart, true, true);
	}

	public static void dismountTo(ServerPlayer player, ServerLevel level, BlockLocation exit, float yaw) {
		player.stopRiding();
		player.teleportTo(level, exit.x() + 0.5, exit.y(), exit.z() + 0.5, Set.<Relative>of(), yaw, 0.0F, false);
		player.setDeltaMovement(Vec3.ZERO);
	}

	/** Returns whether the entity is confirmed removed after the discard call. */
	public static boolean discardCart(Minecart cart) {
		if (!cart.isRemoved()) {
			cart.ejectPassengers();
			cart.discard();
		}
		return cart.isRemoved();
	}

	public static String tripTag(TripId tripId) {
		return TRIP_TAG_PREFIX + tripId.value().toString().replace("-", "");
	}
}
