package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.RailAxis;
import dev.nikhcevov.railway.domain.RoutePlan;
import dev.nikhcevov.railway.network.RailInfo;
import dev.nikhcevov.railway.network.RailKind;
import dev.nikhcevov.railway.network.RailProbe;
import dev.nikhcevov.railway.network.RailView;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Minecraft adapter for rails, exits, dock occupancy and route readiness. */
public final class RailWorld implements RailView {
	private static final Set<Block> HAZARDS = Set.of(
			Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK,
			Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.POWDER_SNOW,
			Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE
	);

	private static final AABB ALL_ENTITIES = new AABB(-3.0E7, -2048, -3.0E7, 3.0E7, 2048, 3.0E7);

	private final ServerLevel level;

	public RailWorld(ServerLevel level) {
		this.level = level;
	}

	public static ServerLevel level(MinecraftServer server, String dimension) {
		for (ServerLevel candidate : server.getAllLevels()) {
			if (dimension(candidate).equals(dimension)) {
				return candidate;
			}
		}
		return null;
	}

	public static String dimension(ServerLevel level) {
		return level.dimension().identifier().toString();
	}

	public ServerLevel level() {
		return level;
	}

	@Override
	public RailProbe probe(BlockLocation location) {
		BlockPos pos = pos(location);
		if (!level.isLoaded(pos)) {
			return RailProbe.unloaded();
		}
		BlockState state = level.getBlockState(pos);
		if (!BaseRailBlock.isRail(state)) {
			return RailProbe.empty();
		}
		RailShape shape = state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
		RailAxis axis = switch (shape) {
			case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> RailAxis.Z;
			case EAST_WEST, ASCENDING_EAST, ASCENDING_WEST -> RailAxis.X;
			default -> null;
		};
		boolean straight = switch (shape) {
			case NORTH_SOUTH, EAST_WEST, ASCENDING_NORTH, ASCENDING_SOUTH, ASCENDING_EAST, ASCENDING_WEST -> true;
			default -> false;
		};
		RailKind kind;
		if (state.is(Blocks.POWERED_RAIL)) {
			kind = RailKind.POWERED;
		} else if (state.is(Blocks.DETECTOR_RAIL)) {
			kind = RailKind.DETECTOR;
		} else if (state.is(Blocks.ACTIVATOR_RAIL)) {
			kind = RailKind.ACTIVATOR;
		} else {
			kind = RailKind.NORMAL;
		}
		return RailProbe.rail(new RailInfo(axis, kind, straight, shape.isSlope()));
	}

	public static BlockLocation loc(BlockPos pos) {
		return new BlockLocation(pos.getX(), pos.getY(), pos.getZ());
	}

	public static BlockPos pos(BlockLocation location) {
		return new BlockPos(location.x(), location.y(), location.z());
	}

	public static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	public boolean isStoneButton(BlockLocation location) {
		return level.isLoaded(pos(location)) && level.getBlockState(pos(location)).is(Blocks.STONE_BUTTON);
	}

	public String blockIdAt(BlockLocation location) {
		if (!level.isLoaded(pos(location))) {
			return "";
		}
		return blockId(level.getBlockState(pos(location)));
	}

	public boolean isSafeStand(BlockLocation feet) {
		BlockLocation head = feet.offset(0, 1, 0);
		BlockLocation below = feet.offset(0, -1, 0);
		if (level.isOutsideBuildHeight(feet.y()) || level.isOutsideBuildHeight(head.y())) {
			return false;
		}
		if (!level.isLoaded(pos(feet)) || !level.isLoaded(pos(head)) || !level.isLoaded(pos(below))) {
			return false;
		}
		BlockState support = level.getBlockState(pos(below));
		if (!support.isCollisionShapeFullBlock(level, pos(below))) {
			return false;
		}
		BlockState atFeet = level.getBlockState(pos(feet));
		BlockState atHead = level.getBlockState(pos(head));
		if (isHazard(support) || isHazard(atFeet) || isHazard(atHead)) {
			return false;
		}
		if (!atFeet.getFluidState().isEmpty() || !atHead.getFluidState().isEmpty()) {
			return false;
		}
		return atFeet.getCollisionShape(level, pos(feet)).isEmpty()
				&& atHead.getCollisionShape(level, pos(head)).isEmpty();
	}

	private static boolean isHazard(BlockState state) {
		return HAZARDS.contains(state.getBlock());
	}

	/** Deterministic search: same level first, then one above/below, nearest first. */
	public Optional<BlockLocation> findSafeSpot(BlockLocation center, int radius) {
		BlockLocation best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int dy : new int[]{0, 1, -1}) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (dx * dx + dz * dz > radius * radius) {
						continue;
					}
					BlockLocation candidate = center.offset(dx, dy, dz);
					if (!isSafeStand(candidate)) {
						continue;
					}
					double distance = dx * dx + dz * dz + Math.abs(dy) * 4.0;
					if (distance < bestDistance) {
						bestDistance = distance;
						best = candidate;
					}
				}
			}
		}
		return Optional.ofNullable(best);
	}

	public boolean isDockFree(BlockLocation dock, UUID passengerUuid) {
		BlockPos pos = pos(dock);
		AABB box = new AABB(pos.getX() + 0.1, pos.getY(), pos.getZ() + 0.1,
				pos.getX() + 0.9, pos.getY() + 1.2, pos.getZ() + 0.9);
		return level.getEntities((Entity) null, box, entity -> !entity.getUUID().equals(passengerUuid)).isEmpty();
	}

	public boolean isPathOccupied(RoutePlan plan, UUID passengerUuid, UUID cartUuid) {
		AABB box = planBox(plan, 0.5);
		List<Entity> entities = level.getEntities((Entity) null, box, entity -> {
			if (entity instanceof AbstractMinecart cart) {
				return !cart.getUUID().equals(cartUuid);
			}
			return entity instanceof LivingEntity && !entity.getUUID().equals(passengerUuid);
		});
		return !entities.isEmpty();
	}

	public boolean isPathBlockedAhead(RoutePlan plan, double cartCoordinate, double lookahead,
			UUID cartUuid, UUID passengerUuid) {
		int sign = plan.departureSign();
		double target = cartCoordinate + sign * lookahead;
		AABB box = planBox(plan, 0.5, cartCoordinate, target);
		return !level.getEntities((Entity) null, box, entity -> {
			if (entity.getUUID().equals(passengerUuid)) {
				return false;
			}
			if (entity instanceof AbstractMinecart cart) {
				return !cart.getUUID().equals(cartUuid);
			}
			return entity instanceof LivingEntity;
		}).isEmpty();
	}

	private AABB planBox(RoutePlan plan, double margin) {
		int from = plan.axis() == RailAxis.X ? plan.fromDock().x() : plan.fromDock().z();
		int to = plan.axis() == RailAxis.X ? plan.toDock().x() : plan.toDock().z();
		return planBox(plan, margin, from, to);
	}

	private AABB planBox(RoutePlan plan, double margin, double a, double b) {
		double min = Math.min(a, b) + 0.5;
		double max = Math.max(a, b) + 0.5;
		int y = plan.fromDock().y();
		if (plan.axis() == RailAxis.X) {
			double z = plan.fromDock().z() + 0.5;
			return new AABB(min - margin, y - 0.5, z - margin, max + margin, y + 1.5, z + margin);
		}
		double x = plan.fromDock().x() + 0.5;
		return new AABB(x - margin, y - 0.5, min - margin, x + margin, y + 1.5, max + margin);
	}

	/** Returns null when the whole route is ready, otherwise the first blocking failure. */
	public Failure validateRoute(RoutePlan plan) {
		int from = plan.axis() == RailAxis.X ? plan.fromDock().x() : plan.fromDock().z();
		int to = plan.axis() == RailAxis.X ? plan.toDock().x() : plan.toDock().z();
		int sign = Integer.compare(to, from);
		if (sign == 0) {
			return Failure.at(FailureCode.NO_PATH, plan.fromDock());
		}
		int y = plan.fromDock().y();
		for (int coord = from; ; coord += sign) {
			BlockLocation location = plan.axis() == RailAxis.X
					? new BlockLocation(coord, y, plan.fromDock().z())
					: new BlockLocation(plan.fromDock().x(), y, coord);
			BlockPos pos = pos(location);
			if (!level.isLoaded(pos) || !level.isPositionEntityTicking(pos)) {
				return Failure.at(FailureCode.WORLD_NOT_LOADED, location);
			}
			RailProbe probe = probe(location);
			if (probe instanceof RailProbe.Unloaded) {
				return Failure.at(FailureCode.WORLD_NOT_LOADED, location);
			}
			if (!(probe instanceof RailProbe.Rail rail)) {
				return Failure.at(FailureCode.SCAN_INCOMPLETE, location);
			}
			RailInfo info = rail.info();
			if (!info.straight() || info.slope() || info.axis() != plan.axis()
					|| info.kind() == RailKind.DETECTOR || info.kind() == RailKind.ACTIVATOR) {
				return Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, location);
			}
			if (coord == to) {
				break;
			}
		}
		return null;
	}

	public static AABB allEntities() {
		return ALL_ENTITIES;
	}
}
