package dev.nikhcevov.railway.gametest;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.network.RailShapeType;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;

import java.lang.reflect.Method;

/** Verifies the managed-cart speed clamp used by the 12 b/s target. */
public final class SpeedGameTests implements CustomTestMethodInvoker {
	@GameTest(maxTicks = 60)
	public void managedCartHoldsTwelveBlocksPerSecond(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		for (int x = 0; x <= 7; x++) {
			helper.setBlock(new BlockPos(x, 0, 0), Blocks.STONE);
			BlockPos railPos = helper.absolutePos(new BlockPos(x, 1, 0));
			level.setBlock(railPos, Blocks.RAIL.defaultBlockState()
					.setValue(RailBlock.SHAPE, RailWorld.vanillaShape(RailShapeType.EAST_WEST)), 2);
		}
		BlockLocation dock = RailWorld.loc(helper.absolutePos(new BlockPos(0, 1, 0)));
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.teleportTo(dock.x() + 0.5, dock.y(), dock.z() + 0.5);
		Minecart cart = PlayerActions.spawnServiceCart(level, dock, TripId.random());
		if (cart == null) {
			helper.fail("could not spawn service cart");
			return;
		}
		if (!PlayerActions.mount(player, cart)) {
			helper.fail("could not mount speed-test passenger");
			return;
		}
		double startX = cart.getX();
		helper.onEachTick(() -> cart.setDeltaMovement(0.6, 0.0, 0.0));
		helper.runAtTickTime(6, () -> {
			double moved = Math.abs(cart.getX() - startX);
			if (moved < 2.3) {
				helper.fail("managed cart did not hold 12 b/s: moved " + moved);
			}
			if (cart.getDeltaMovement().x() < 0.5) {
				helper.fail("managed cart velocity was clamped: " + cart.getDeltaMovement());
			}
			PlayerActions.discardCart(cart);
			helper.succeed();
		});
	}

	@Override
	public void invokeTestMethod(GameTestHelper context, Method method) throws ReflectiveOperationException {
		method.invoke(this, context);
	}
}
