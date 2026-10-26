package dev.nikhcevov.railway.minecraft;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.network.GraphRailView;
import dev.nikhcevov.railway.network.GraphRoutePlan;
import dev.nikhcevov.railway.network.RailKind;
import dev.nikhcevov.railway.network.RailShapeType;
import dev.nikhcevov.railway.network.RouteStep;
import dev.nikhcevov.railway.network.SwitchRequirement;

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
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Minecraft adapter for rails, exits, dock occupancy and route readiness. */
public final class RailWorld implements GraphRailView {
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
	public Probe graphProbe(BlockLocation location) {
		BlockPos pos = pos(location);
		if (!level.isLoaded(pos)) {
			return new Probe.Unloaded();
		}
		BlockState state = level.getBlockState(pos);
		if (!BaseRailBlock.isRail(state)) {
			return new Probe.Empty();
		}
		RailShape shape = state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
		return new Probe.Rail(shapeType(shape), kindOf(state));
	}

	private static RailKind kindOf(BlockState state) {
		if (state.is(Blocks.POWERED_RAIL)) {
			return RailKind.POWERED;
		}
		if (state.is(Blocks.DETECTOR_RAIL)) {
			return RailKind.DETECTOR;
		}
		if (state.is(Blocks.ACTIVATOR_RAIL)) {
			return RailKind.ACTIVATOR;
		}
		return RailKind.NORMAL;
	}

	private static RailShapeType shapeType(RailShape shape) {
		return switch (shape) {
			case NORTH_SOUTH -> RailShapeType.NORTH_SOUTH;
			case EAST_WEST -> RailShapeType.EAST_WEST;
			case ASCENDING_EAST -> RailShapeType.ASCENDING_EAST;
			case ASCENDING_WEST -> RailShapeType.ASCENDING_WEST;
			case ASCENDING_NORTH -> RailShapeType.ASCENDING_NORTH;
			case ASCENDING_SOUTH -> RailShapeType.ASCENDING_SOUTH;
			case SOUTH_EAST -> RailShapeType.SOUTH_EAST;
			case SOUTH_WEST -> RailShapeType.SOUTH_WEST;
			case NORTH_WEST -> RailShapeType.NORTH_WEST;
			case NORTH_EAST -> RailShapeType.NORTH_EAST;
		};
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

	public RailShapeType shapeAt(BlockLocation location) {
		return graphProbe(location) instanceof Probe.Rail rail ? rail.shape() : null;
	}

	/**
	 * Writes a rail shape without neighbor updates and verifies the result by
	 * re-reading it. The command/API result is not trusted (observed to report
	 * failure while still applying the state).
	 */
	public boolean setRailShape(BlockLocation location, RailShapeType shape) {
		BlockPos pos = pos(location);
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState state = level.getBlockState(pos);
		if (!(state.getBlock() instanceof BaseRailBlock railBlock)) {
			return false;
		}
		Property<RailShape> property = railBlock.getShapeProperty();
		if (!state.hasProperty(property)) {
			return false;
		}
		BlockState target = state.setValue(property, vanillaShape(shape));
		level.setBlock(pos, target, 2);
		return target.equals(level.getBlockState(pos));
	}

	public Failure validateGraphRoute(GraphRoutePlan plan) {
		java.util.Set<BlockLocation> switches = new java.util.HashSet<>();
		for (SwitchRequirement requirement : plan.switches()) {
			switches.add(requirement.pos());
		}
		for (RouteStep step : plan.steps()) {
			BlockLocation location = step.pos();
			BlockPos pos = pos(location);
			if (!level.isLoaded(pos) || !level.isPositionEntityTicking(pos)) {
				return Failure.at(FailureCode.WORLD_NOT_LOADED, location);
			}
			if (!(graphProbe(location) instanceof Probe.Rail rail)) {
				return Failure.at(FailureCode.SCAN_INCOMPLETE, location);
			}
			if (rail.kind() == RailKind.DETECTOR || rail.kind() == RailKind.ACTIVATOR) {
				return Failure.at(FailureCode.UNSUPPORTED_RAIL, location, rail.kind().name());
			}
			if (!switches.contains(location) && rail.shape() != step.shape()) {
				return Failure.at(FailureCode.SCAN_INCOMPLETE, location, "rail shape changed");
			}
		}
		return null;
	}

	/** Sets and re-reads every turnout shape required by the plan. */
	public Failure prepareSwitches(GraphRoutePlan plan) {
		for (SwitchRequirement requirement : plan.switches()) {
			if (!setRailShape(requirement.pos(), requirement.requiredShape())
					|| shapeAt(requirement.pos()) != requirement.requiredShape()) {
				return Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, requirement.pos(), "turnout write failed");
			}
		}
		return null;
	}

	public boolean isPathOccupied(GraphRoutePlan plan, UUID passengerUuid, UUID cartUuid) {
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int minZ = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		int maxZ = Integer.MIN_VALUE;
		for (RouteStep step : plan.steps()) {
			minX = Math.min(minX, step.pos().x());
			minY = Math.min(minY, step.pos().y());
			minZ = Math.min(minZ, step.pos().z());
			maxX = Math.max(maxX, step.pos().x());
			maxY = Math.max(maxY, step.pos().y());
			maxZ = Math.max(maxZ, step.pos().z());
		}
		AABB box = new AABB(minX - 0.5, minY - 0.5, minZ - 0.5,
				maxX + 1.5, maxY + 2.0, maxZ + 1.5);
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

	public static RailShape vanillaShape(RailShapeType shape) {
		return switch (shape) {
			case NORTH_SOUTH -> RailShape.NORTH_SOUTH;
			case EAST_WEST -> RailShape.EAST_WEST;
			case ASCENDING_EAST -> RailShape.ASCENDING_EAST;
			case ASCENDING_WEST -> RailShape.ASCENDING_WEST;
			case ASCENDING_NORTH -> RailShape.ASCENDING_NORTH;
			case ASCENDING_SOUTH -> RailShape.ASCENDING_SOUTH;
			case SOUTH_EAST -> RailShape.SOUTH_EAST;
			case SOUTH_WEST -> RailShape.SOUTH_WEST;
			case NORTH_WEST -> RailShape.NORTH_WEST;
			case NORTH_EAST -> RailShape.NORTH_EAST;
		};
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

	public static AABB allEntities() {
		return ALL_ENTITIES;
	}
}
