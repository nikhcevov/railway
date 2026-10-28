package dev.nikhcevov.railway.fixture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dev-only {@code /railfixture} surface. It is registered by
 * {@code RailwayMod} only through {@link FixtureEnvironment}; a production jar
 * never exposes it.
 */
public final class FixtureCommand {
	private static final String WIDE_FRONTIER = "wide-frontier";
	private static final SuggestionProvider<CommandSourceStack> SCENARIOS = (context, builder) -> {
		builder.suggest(LongRouteScenario.SCENARIO);
		builder.suggest(WIDE_FRONTIER);
		return builder.buildFuture();
	};
	private static final SuggestionProvider<CommandSourceStack> STATIONS = (context, builder) -> {
		builder.suggest("home");
		builder.suggest("mine");
		return builder.buildFuture();
	};

	private FixtureCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("railfixture")
				.requires(source -> FixtureEnvironment.enabled())
				.then(Commands.literal("list")
						.executes(FixtureCommand::list)
						.then(Commands.argument("scenario", StringArgumentType.word())
								.suggests(SCENARIOS)
								.executes(FixtureCommand::listOne)))
				.then(Commands.literal("build")
						.then(Commands.argument("scenario", StringArgumentType.word())
								.suggests(SCENARIOS)
								.executes(FixtureCommand::build)))
				.then(Commands.literal("verify")
						.then(Commands.argument("scenario", StringArgumentType.word())
								.suggests(SCENARIOS)
								.executes(FixtureCommand::verify)))
				.then(Commands.literal("report")
						.then(Commands.argument("scenario", StringArgumentType.word())
								.suggests(SCENARIOS)
								.executes(FixtureCommand::report)))
				.then(Commands.literal("tp")
						.then(Commands.argument("scenario", StringArgumentType.word())
								.suggests(SCENARIOS)
								.then(Commands.argument("station", StringArgumentType.word())
										.suggests(STATIONS)
										.executes(FixtureCommand::teleport)))));
	}

	private static int list(CommandContext<CommandSourceStack> context) {
		for (String line : FixtureService.listLines(context.getSource().getServer())) {
			context.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	private static int listOne(CommandContext<CommandSourceStack> context) {
		String scenario = StringArgumentType.getString(context, "scenario");
		if (WIDE_FRONTIER.equals(scenario)) {
			context.getSource().sendSuccess(() -> Component.literal(wideFrontierBlocker()), false);
			return 1;
		}
		if (!LongRouteScenario.SCENARIO.equals(scenario)) {
			return unknownScenario(context);
		}
		return list(context);
	}

	private static int build(CommandContext<CommandSourceStack> context) {
		if (!requireLongRoute(context)) {
			return 0;
		}
		Railway railway = railway(context);
		if (railway == null) {
			return 0;
		}
		ServerLevel level = context.getSource().getServer().overworld();
		try {
			FixtureService.BuildOutcome outcome = FixtureService.build(railway, level);
			LongRouteScenario.BuildResult result = outcome.result();
			context.getSource().sendSuccess(() -> Component.literal(
					"Fixture " + LongRouteScenario.SCENARIO + " built: " + result.rails()
							+ " rails, " + result.signs() + " signs, " + result.buttons()
							+ " buttons, " + result.chunks() + " chunks"), false);
			context.getSource().sendSuccess(() -> Component.literal(
					"Origin " + result.origin().shortString() + "; report " + outcome.report()), false);
			for (String note : result.notes()) {
				context.getSource().sendSuccess(() -> Component.literal("  " + note), false);
			}
			if (!outcome.failures().isEmpty()) {
				for (String failure : outcome.failures()) {
					context.getSource().sendFailure(Component.literal("  " + failure));
				}
				return 0;
			}
			return 1;
		} catch (FixtureException e) {
			context.getSource().sendFailure(Component.literal("Fixture build failed: " + e.getMessage()));
			return 0;
		}
	}

	private static int verify(CommandContext<CommandSourceStack> context) {
		if (!requireLongRoute(context)) {
			return 0;
		}
		Railway railway = railway(context);
		if (railway == null) {
			return 0;
		}
		ServerLevel level = context.getSource().getServer().overworld();
		try {
			FixtureService.startVerify(railway, level);
			Path report = FixtureService.reportPath(context.getSource().getServer(),
					LongRouteScenario.SCENARIO, "verify");
			context.getSource().sendSuccess(() -> Component.literal(
					"Fixture verify started; waiting for the destination chunk to unload, then scanning"),
					false);
			context.getSource().sendSuccess(() -> Component.literal("Report will be written to " + report),
					false);
			return 1;
		} catch (FixtureException e) {
			context.getSource().sendFailure(Component.literal("Fixture verify failed: " + e.getMessage()));
			return 0;
		}
	}

	private static int report(CommandContext<CommandSourceStack> context) {
		String scenario = StringArgumentType.getString(context, "scenario");
		if (WIDE_FRONTIER.equals(scenario)) {
			context.getSource().sendFailure(Component.literal(wideFrontierBlocker()));
			return 0;
		}
		if (!LongRouteScenario.SCENARIO.equals(scenario)) {
			return unknownScenario(context);
		}
		Path verify = FixtureService.reportPath(context.getSource().getServer(),
				LongRouteScenario.SCENARIO, "verify");
		Path build = FixtureService.reportPath(context.getSource().getServer(),
				LongRouteScenario.SCENARIO, "build");
		Path path = Files.isRegularFile(verify) ? verify : Files.isRegularFile(build) ? build : null;
		if (path == null) {
			context.getSource().sendFailure(Component.literal(
					"No fixture report yet; run /railfixture build or verify first"));
			return 0;
		}
		try {
			List<String> lines = Files.readAllLines(path);
			context.getSource().sendSuccess(() -> Component.literal("Fixture report " + path), false);
			for (String line : lines) {
				context.getSource().sendSuccess(() -> Component.literal(line), false);
			}
			return 1;
		} catch (IOException e) {
			context.getSource().sendFailure(Component.literal(
					"Cannot read fixture report " + path + ": " + e.getMessage()));
			return 0;
		}
	}

	private static int teleport(CommandContext<CommandSourceStack> context) {
		ServerPlayer player = context.getSource().getPlayer();
		if (player == null) {
			context.getSource().sendFailure(Component.literal("railfixture tp needs a player source"));
			return 0;
		}
		if (!requireLongRoute(context)) {
			return 0;
		}
		String station = StringArgumentType.getString(context, "station");
		LongRouteLayout layout = LongRouteLayout.DEFAULT;
		BlockLocation target = switch (station) {
			case "home" -> layout.homeExit();
			case "mine" -> layout.destinationExit();
			default -> null;
		};
		if (target == null) {
			context.getSource().sendFailure(Component.literal("Unknown station; use home or mine"));
			return 0;
		}
		ServerLevel level = context.getSource().getServer().overworld();
		PlayerActions.dismountTo(player, level, target, 0.0F);
		context.getSource().sendSuccess(() -> Component.literal(
				"Teleported to fixture " + station + " at " + target.shortString()), false);
		return 1;
	}

	private static boolean requireLongRoute(CommandContext<CommandSourceStack> context) {
		String scenario = StringArgumentType.getString(context, "scenario");
		if (LongRouteScenario.SCENARIO.equals(scenario)) {
			return true;
		}
		if (WIDE_FRONTIER.equals(scenario)) {
			context.getSource().sendFailure(Component.literal(wideFrontierBlocker()));
			return false;
		}
		unknownScenario(context);
		return false;
	}

	private static int unknownScenario(CommandContext<CommandSourceStack> context) {
		context.getSource().sendFailure(Component.literal(
				"Unknown fixture scenario; use " + LongRouteScenario.SCENARIO));
		return 0;
	}

	private static Railway railway(CommandContext<CommandSourceStack> context) {
		if (!Railway.isStarted()) {
			context.getSource().sendFailure(Component.literal("Railway is not initialized"));
			return null;
		}
		Railway railway = Railway.get();
		if (railway.disabled()) {
			context.getSource().sendFailure(Component.literal(
					"Railway is disabled: " + railway.disabledReason()));
			return null;
		}
		return railway;
	}

	private static String wideFrontierBlocker() {
		return "wide-frontier is not implemented: a real rail graph discovers by one-block adjacency, "
				+ "so exposing more than 32 distinct unloaded frontier chunks requires a force-loaded "
				+ "hub ring plus artificial dead-end stubs rather than a real route. The GameTest "
				+ "scanFrontierOverTheCap... keeps selector coverage; see docs/fixtures.md.";
	}
}
