package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.network.RailShapeType;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level.ExplosionInteraction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

/**
 * A hit on a service cart must not destroy it into a dropped minecart item or
 * eject the passenger. Ordinary carts keep vanilla damage and drops.
 */
public final class DamageGameTests extends GameTestSupport {
	@GameTest(maxTicks = 40)
	public void managedCartIgnoresDamageAndKeepsRider(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockLocation dock = prepareDock(helper);
		ServerPlayer rider = helper.makeMockServerPlayerInLevel();
		rider.teleportTo(dock.x() + 0.5, dock.y(), dock.z() + 0.5);
		Minecart cart = PlayerActions.spawnServiceCart(level, dock, TripId.random());
		if (cart == null || !PlayerActions.mount(rider, cart)) {
			throw helper.assertionException("could not spawn or mount service cart");
		}
		ServerPlayer attacker = helper.makeMockServerPlayerInLevel();
		// Survival sword damage destroys a vanilla cart in one hit (damage * 10 > 40).
		cart.hurtServer(level, level.damageSources().playerAttack(attacker), 7.0F);
		if (cart.isRemoved()) {
			throw helper.assertionException("managed cart was destroyed by a player hit");
		}
		if (rider.getVehicle() != cart) {
			throw helper.assertionException("managed cart hit ejected the rider");
		}
		if (minecartItemCount(helper, level) != 0) {
			throw helper.assertionException("managed cart hit dropped a minecart item");
		}
		PlayerActions.discardCart(cart);
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void ordinaryCartStillBreaksAndDrops(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockLocation dock = prepareDock(helper);
		Minecart cart = EntityType.MINECART.create(level, EntitySpawnReason.COMMAND);
		if (cart == null) {
			throw helper.assertionException("could not create test cart");
		}
		cart.setPos(dock.x() + 0.5, dock.y(), dock.z() + 0.5);
		cart.setDeltaMovement(Vec3.ZERO);
		if (!level.addFreshEntity(cart)) {
			throw helper.assertionException("could not add test cart");
		}
		cart.hurtServer(level, level.damageSources().generic(), 5.0F);
		if (!cart.isRemoved()) {
			throw helper.assertionException("ordinary cart ignored fatal damage");
		}
		if (minecartItemCount(helper, level) != 1) {
			throw helper.assertionException("ordinary cart did not drop a minecart item");
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void managedCartIgnoresExplosion(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockLocation dock = prepareDock(helper);
		Minecart cart = PlayerActions.spawnServiceCart(level, dock, TripId.random());
		if (cart == null) {
			throw helper.assertionException("could not spawn service cart");
		}
		level.explode(null, cart.getX(), cart.getY(), cart.getZ(), 3.0F, ExplosionInteraction.NONE);
		if (cart.isRemoved()) {
			throw helper.assertionException("managed cart was destroyed by an explosion");
		}
		if (cart.getDeltaMovement().lengthSqr() > 1.0e-9) {
			throw helper.assertionException("managed cart was pushed by an explosion: " + cart.getDeltaMovement());
		}
		if (minecartItemCount(helper, level) != 0) {
			throw helper.assertionException("explosion dropped a minecart item");
		}
		PlayerActions.discardCart(cart);
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void ordinaryCartStillTakesExplosionDamage(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockLocation dock = prepareDock(helper);
		Minecart cart = EntityType.MINECART.create(level, EntitySpawnReason.COMMAND);
		if (cart == null) {
			throw helper.assertionException("could not create test cart");
		}
		cart.setPos(dock.x() + 0.5, dock.y(), dock.z() + 0.5);
		cart.setDeltaMovement(Vec3.ZERO);
		if (!level.addFreshEntity(cart)) {
			throw helper.assertionException("could not add test cart");
		}
		level.explode(null, cart.getX(), cart.getY(), cart.getZ(), 3.0F, ExplosionInteraction.NONE);
		if (!cart.isRemoved()) {
			throw helper.assertionException("ordinary cart ignored explosion damage");
		}
		if (minecartItemCount(helper, level) == 0) {
			throw helper.assertionException("ordinary cart explosion did not drop a minecart item");
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 40)
	public void managedCartIsNotPushableButVanillaCartIs(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockLocation dock = prepareDock(helper);
		Minecart managed = PlayerActions.spawnServiceCart(level, dock, TripId.random());
		if (managed == null) {
			throw helper.assertionException("could not spawn service cart");
		}
		if (managed.isPushable()) {
			throw helper.assertionException("managed cart is still pushable");
		}
		Minecart vanilla = EntityType.MINECART.create(level, EntitySpawnReason.COMMAND);
		if (vanilla == null) {
			throw helper.assertionException("could not create test cart");
		}
		vanilla.setPos(dock.x() + 2.5, dock.y(), dock.z() + 0.5);
		if (!level.addFreshEntity(vanilla)) {
			throw helper.assertionException("could not add test cart");
		}
		if (!vanilla.isPushable()) {
			throw helper.assertionException("ordinary cart lost vanilla pushability");
		}
		PlayerActions.discardCart(managed);
		vanilla.discard();
		helper.succeed();
	}

	private static BlockLocation prepareDock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x <= 3; x++) {
			helper.setBlock(new BlockPos(x, 0, 0), Blocks.STONE);
			level.setBlock(helper.absolutePos(new BlockPos(x, 1, 0)), Blocks.RAIL.defaultBlockState()
					.setValue(RailBlock.SHAPE, RailWorld.vanillaShape(RailShapeType.EAST_WEST)), 2);
		}
		return RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0)));
	}

	private static int minecartItemCount(GameTestHelper helper, ServerLevel level) {
		EntityTypeTest<Entity, ItemEntity> items = EntityTypeTest.forClass(ItemEntity.class);
		return level.getEntities(items, helper.getBounds().inflate(2.0),
				item -> item.getItem().is(Items.MINECART)).size();
	}
}
