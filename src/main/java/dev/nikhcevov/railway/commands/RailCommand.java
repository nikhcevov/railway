package dev.nikhcevov.railway.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import dev.nikhcevov.railway.domain.Binding;
import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.SignMarker;
import dev.nikhcevov.railway.domain.Station;
import dev.nikhcevov.railway.domain.StationId;
import dev.nikhcevov.railway.domain.StationNames;
import dev.nikhcevov.railway.domain.Trip;
import dev.nikhcevov.railway.domain.TripId;
import dev.nikhcevov.railway.minecraft.PlayerActions;
import dev.nikhcevov.railway.minecraft.RailWorld;
import dev.nikhcevov.railway.minecraft.Railway;
import dev.nikhcevov.railway.minecraft.ScanService;
import dev.nikhcevov.railway.minecraft.TripService;
import dev.nikhcevov.railway.network.RailKind;
import dev.nikhcevov.railway.network.GraphRailView;
import dev.nikhcevov.railway.network.GraphRoutePlan;
import dev.nikhcevov.railway.network.RailGraph;
import dev.nikhcevov.railway.network.RailGraphPlanner;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Public {@code /rail} command surface for stage 1. */
public final class RailCommand {
	private static final double TARGET_RANGE = 5.0;
	private static final Predicate<CommandSourceStack> OP2 =
			source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);

	private static final SuggestionProvider<CommandSourceStack> STATIONS = (context, builder) -> {
		if (Railway.isStarted()) {
			for (Station station : Railway.get().state().stations()) {
				builder.suggest(station.id().value());
			}
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<CommandSourceStack> TRIPS = (context, builder) -> {
		if (Railway.isStarted()) {
			for (Trip trip : Railway.get().trips().activeTrips()) {
				builder.suggest(trip.id().toString());
			}
		}
		return builder.buildFuture();
	};

	private RailCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("rail")
				.then(Commands.literal("help").executes(RailCommand::help))
				.then(Commands.literal("station")
						.then(Commands.literal("create").requires(OP2)
								.then(Commands.argument("id", StringArgumentType.word())
										.then(Commands.argument("displayName", StringArgumentType.greedyString())
												.executes(RailCommand::stationCreate))))
						.then(Commands.literal("exit").requires(OP2)
								.then(Commands.argument("id", StringArgumentType.word()).suggests(STATIONS)
										.executes(RailCommand::stationExit)))
						.then(Commands.literal("rename").requires(OP2)
								.then(Commands.argument("id", StringArgumentType.word()).suggests(STATIONS)
										.then(Commands.argument("displayName", StringArgumentType.greedyString())
												.executes(RailCommand::stationRename))))
						.then(Commands.literal("enable").requires(OP2)
								.then(Commands.argument("id", StringArgumentType.word()).suggests(STATIONS)
										.executes(RailCommand::stationEnable)))
						.then(Commands.literal("disable").requires(OP2)
								.then(Commands.argument("id", StringArgumentType.word()).suggests(STATIONS)
										.executes(RailCommand::stationDisable)))
						.then(Commands.literal("info")
								.then(Commands.argument("id", StringArgumentType.word()).suggests(STATIONS)
										.executes(RailCommand::stationInfo)))
						.then(Commands.literal("list").executes(RailCommand::stationList))
						.then(Commands.literal("remove").requires(OP2)
								.then(Commands.argument("id", StringArgumentType.word()).suggests(STATIONS)
										.executes(RailCommand::stationRemove))))
				.then(Commands.literal("button")
						.then(Commands.literal("bind").requires(OP2)
								.then(Commands.argument("from", StringArgumentType.word()).suggests(STATIONS)
										.then(Commands.argument("to", StringArgumentType.word()).suggests(STATIONS)
												.executes(RailCommand::buttonBind))))
						.then(Commands.literal("info").executes(RailCommand::buttonInfo))
						.then(Commands.literal("unbind").requires(OP2).executes(RailCommand::buttonUnbind)))
				.then(Commands.literal("network")
						.then(Commands.literal("scan").requires(OP2)
								.then(Commands.argument("station", StringArgumentType.word()).suggests(STATIONS)
										.executes(RailCommand::networkScan)))
						.then(Commands.literal("status")
								.executes(RailCommand::networkStatusAll)
								.then(Commands.argument("station", StringArgumentType.word()).suggests(STATIONS)
										.executes(RailCommand::networkStatus))))
				.then(Commands.literal("route")
						.then(Commands.literal("check")
								.then(Commands.argument("from", StringArgumentType.word()).suggests(STATIONS)
										.then(Commands.argument("to", StringArgumentType.word()).suggests(STATIONS)
												.executes(RailCommand::routeCheck)))))
				.then(Commands.literal("trip")
						.then(Commands.literal("list").executes(RailCommand::tripList))
						.then(Commands.literal("abort").requires(OP2)
								.then(Commands.argument("tripId", StringArgumentType.word()).suggests(TRIPS)
										.executes(RailCommand::tripAbort))))
				.then(Commands.literal("diagnostics").requires(OP2)
						.executes(RailCommand::diagnostics)
						.then(Commands.argument("station", StringArgumentType.word()).suggests(STATIONS)
								.executes(RailCommand::diagnosticsStation)))
				.then(Commands.literal("reload").requires(OP2).executes(RailCommand::reload)));
	}

	// ------------------------------------------------------------------- help

	private static int help(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		for (String key : List.of("command.help.header", "command.help.station", "command.help.button",
				"command.help.network", "command.help.route", "command.help.trip",
				"command.help.diagnostics", "command.help.reload", "command.help.op")) {
			source.sendSuccess(() -> Messages.text(source, key), false);
		}
		return 1;
	}

	// ---------------------------------------------------------------- station

	private static int stationCreate(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		ServerPlayer player = player(context.getSource());
		if (railway == null || player == null) {
			return 0;
		}
		String rawId = StringArgumentType.getString(context, "id");
		String displayName = StationNames.normalize(StringArgumentType.getString(context, "displayName"));
		if (!StationId.isValid(rawId)) {
			return fail(context.getSource(), Failure.detail(FailureCode.INVALID_ID, rawId));
		}
		if (displayName.isEmpty()) {
			return fail(context.getSource(), Failure.of(FailureCode.INVALID_NAME));
		}
		StationId id = new StationId(rawId);
		if (railway.state().station(id).isPresent()) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_EXISTS, rawId));
		}
		ServerLevel level = player.level();
		String dimension = RailWorld.dimension(level);
		if (railway.state().hasStationNamed(dimension, displayName)) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_NAME_TAKEN, displayName));
		}
		BlockLocation dock = targetedBlock(player, context.getSource());
		if (dock == null) {
			return 0;
		}
		RailWorld world = new RailWorld(level);
		GraphRailView.Probe probe = world.graphProbe(dock);
		Failure dockFailure = dockFailure(probe, dock);
		if (dockFailure != null) {
			return fail(context.getSource(), dockFailure);
		}
		Station station = new Station(id, displayName, dimension, dock, null, 0.0F, false,
				railway.state().nextRevision());
		railway.state().addStation(station);
		railway.saveState();
		return ok(context.getSource(), "station.created", rawId, rawId);
	}

	private static int stationExit(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		ServerPlayer player = player(context.getSource());
		if (railway == null || player == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "id"));
		if (station == null) {
			return 0;
		}
		if (station.isProvisional()) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_INCOMPLETE, station.displayName()));
		}
		ServerLevel level = RailWorld.level(context.getSource().getServer(), station.dimension());
		if (level == null) {
			return fail(context.getSource(), Failure.detail(FailureCode.WORLD_NOT_LOADED, station.dimension()));
		}
		if (player.level() != level) {
			return fail(context.getSource(), Failure.of(FailureCode.DIMENSION_MISMATCH));
		}
		BlockLocation exit = RailWorld.loc(player.blockPosition());
		RailWorld world = new RailWorld(level);
		if (!world.isSafeStand(exit)) {
			return fail(context.getSource(), Failure.at(FailureCode.EXIT_UNSAFE, exit));
		}
		Station updated = station.withExit(exit, player.getYRot(), railway.state().nextRevision());
		railway.state().updateStation(updated);
		railway.saveState();
		return ok(context.getSource(), "station.exit_set", station.id().value(), exit.shortString());
	}

	private static int stationRename(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "id"));
		String name = StationNames.normalize(StringArgumentType.getString(context, "displayName"));
		if (station == null) {
			return 0;
		}
		if (name.isEmpty()) {
			return fail(context.getSource(), Failure.of(FailureCode.INVALID_NAME));
		}
		if (!railway.state().bindingsFor(station.id()).isEmpty()) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_NAME_LOCKED, station.displayName()));
		}
		boolean nameTaken = railway.state().stationsNamed(station.dimension(), name).stream()
				.anyMatch(other -> !other.id().equals(station.id()));
		if (nameTaken) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_NAME_TAKEN, name));
		}
		railway.state().updateStation(station.withDisplayName(name, railway.state().nextRevision()));
		railway.saveState();
		return ok(context.getSource(), "station.renamed", station.id().value(), name);
	}

	private static int stationEnable(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "id"));
		if (station == null) {
			return 0;
		}
		if (station.isProvisional() || !station.hasExit()) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_INCOMPLETE, station.displayName()));
		}
		ServerLevel level = RailWorld.level(context.getSource().getServer(), station.dimension());
		if (level == null) {
			return fail(context.getSource(), Failure.detail(FailureCode.WORLD_NOT_LOADED, station.dimension()));
		}
		RailWorld world = new RailWorld(level);
		Failure dockFailure = dockFailure(world.graphProbe(station.dock()), station.dock());
		if (dockFailure != null) {
			return fail(context.getSource(), dockFailure);
		}
		if (!world.isSafeStand(station.exit())) {
			return fail(context.getSource(), Failure.at(FailureCode.EXIT_UNSAFE, station.exit()));
		}
		railway.state().updateStation(station.withEnabled(true, railway.state().nextRevision()));
		railway.saveState();
		return ok(context.getSource(), "station.enabled", station.id().value());
	}

	private static int stationDisable(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "id"));
		if (station == null) {
			return 0;
		}
		if (railway.trips().stationInUse(station.id())) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_HAS_ACTIVE_TRIP, station.displayName()));
		}
		railway.state().updateStation(station.withEnabled(false, railway.state().nextRevision()));
		railway.saveState();
		return ok(context.getSource(), "station.disabled", station.id().value());
	}

	private static int stationInfo(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "id"));
		if (station == null) {
			return 0;
		}
		CommandSourceStack source = context.getSource();
		Messages.Lang lang = Messages.lang(source);
		source.sendSuccess(() -> Messages.text(source, "station.info.id", station.id().value(), station.displayName()), false);
		source.sendSuccess(() -> Messages.text(source, "station.info.dimension", station.dimension()), false);
		String dockText = station.isProvisional()
				? Messages.format(lang, "station.dock.none")
				: station.dock().shortString();
		source.sendSuccess(() -> Messages.text(source, "station.info.dock", dockText), false);
		source.sendSuccess(() -> station.hasExit()
				? Messages.text(source, "station.info.exit", station.exit().shortString(), station.exitYaw())
				: Messages.text(source, "station.info.exit_missing"), false);
		source.sendSuccess(() -> Messages.text(source, "station.info.state",
				Messages.format(lang, station.enabled() ? "state.enabled" : "state.disabled"), station.revision()), false);
		int signUnits = railway.state().signUnitCount(station.id());
		source.sendSuccess(() -> Messages.text(source, "station.info.units", signUnits), false);
		SignMarker mainMarker = railway.state().mainMarkerForStation(station.id()).orElse(null);
		if (mainMarker != null) {
			source.sendSuccess(() -> Messages.text(source, "station.info.main_marker",
					mainMarker.sign().shortString()), false);
		} else if (station.isAnchored()) {
			source.sendSuccess(() -> Messages.text(source, "station.info.main",
					station.dock().shortString()), false);
		} else {
			source.sendSuccess(() -> Messages.text(source, "station.info.main_missing"), false);
		}
		Collection<Binding> bindings = railway.state().bindingsFor(station.id());
		if (bindings.isEmpty()) {
			source.sendSuccess(() -> Messages.text(source, "station.info.no_bindings"), false);
		} else {
			StringBuilder builder = new StringBuilder();
			for (Binding binding : bindings) {
				if (!builder.isEmpty()) {
					builder.append("; ");
				}
				builder.append(binding.fromStationId().value()).append("->").append(binding.toStationId().value())
						.append(" @ ").append(binding.button().shortString());
			}
			source.sendSuccess(() -> Messages.text(source, "station.info.bindings", builder.toString()), false);
		}
		ScanService.Status status = railway.scans().status(station.id()).orElse(null);
		String scanText = status == null
				? Messages.format(lang, "scan.status.missing", station.id().value())
				: scanStatusText(lang, status);
		source.sendSuccess(() -> Messages.text(source, "station.info.scan", scanText), false);

		ServerLevel level = RailWorld.level(source.getServer(), station.dimension());
		if (level != null && station.isAnchored()) {
			RailWorld world = new RailWorld(level);
			GraphRailView.Probe probe = world.graphProbe(station.dock());
			Failure dockProblem = dockFailure(probe, station.dock());
			if (dockProblem != null) {
				source.sendSuccess(() -> Messages.text(source, "station.info.problem",
						Messages.failureText(lang, dockProblem)), false);
			}
			if (station.hasExit() && !world.isSafeStand(station.exit())) {
				source.sendSuccess(() -> Messages.text(source, "station.info.problem",
						Messages.failureText(lang, Failure.at(FailureCode.EXIT_UNSAFE, station.exit()))), false);
			}
		}
		return 1;
	}

	private static int stationList(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Collection<Station> stations = railway.state().stations();
		CommandSourceStack source = context.getSource();
		if (stations.isEmpty()) {
			source.sendSuccess(() -> Messages.text(source, "station.list.empty"), false);
			return 1;
		}
		Messages.Lang lang = Messages.lang(source);
		for (Station station : stations) {
			String state = Messages.format(lang, station.enabled() ? "state.enabled" : "state.disabled");
			String dockText = station.isProvisional()
					? Messages.format(lang, "station.dock.none")
					: station.dock().shortString();
			source.sendSuccess(() -> Messages.text(source, "station.list.entry", station.id().value(),
					station.displayName(), station.dimension(), dockText, state), false);
		}
		return 1;
	}

	private static int stationRemove(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "id"));
		if (station == null) {
			return 0;
		}
		if (railway.trips().stationInUse(station.id())) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_HAS_ACTIVE_TRIP, station.displayName()));
		}
		List<Binding> removed = railway.state().removeStation(station.id());
		railway.scans().invalidate(station.id());
		railway.saveState();
		return ok(context.getSource(), "station.removed", station.id().value(), removed.size());
	}

	// ----------------------------------------------------------------- button

	private static int buttonBind(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		ServerPlayer player = player(context.getSource());
		if (railway == null || player == null) {
			return 0;
		}
		Station from = station(context.getSource(), StringArgumentType.getString(context, "from"));
		Station to = station(context.getSource(), StringArgumentType.getString(context, "to"));
		if (from == null || to == null) {
			return 0;
		}
		if (from.isProvisional()) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_INCOMPLETE, from.displayName()));
		}
		if (!from.dimension().equals(to.dimension())) {
			return fail(context.getSource(), Failure.of(FailureCode.DIMENSION_MISMATCH));
		}
		if (!from.dimension().equals(RailWorld.dimension(player.level()))) {
			return fail(context.getSource(), Failure.of(FailureCode.DIMENSION_MISMATCH));
		}
		BlockLocation button = targetedBlock(player, context.getSource());
		if (button == null) {
			return 0;
		}
		ServerLevel level = player.level();
		RailWorld world = new RailWorld(level);
		if (!world.isStoneButton(button)) {
			return fail(context.getSource(), Failure.detail(FailureCode.NOT_BUTTON, "minecraft:stone_button"));
		}
		if (railway.state().binding(RailWorld.dimension(level), button).isPresent()) {
			return fail(context.getSource(), Failure.at(FailureCode.BUTTON_ALREADY_BOUND, button));
		}
		Binding binding = new Binding(RailWorld.dimension(level), button, "minecraft:stone_button", from.id(), to.id());
		railway.state().addBinding(binding);
		railway.saveState();
		return ok(context.getSource(), "button.bound", from.id().value(), to.id().value());
	}

	private static int buttonInfo(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		ServerPlayer player = player(context.getSource());
		if (railway == null || player == null) {
			return 0;
		}
		BlockLocation button = targetedBlock(player, context.getSource());
		if (button == null) {
			return 0;
		}
		ServerLevel level = player.level();
		RailWorld world = new RailWorld(level);
		if (!world.isStoneButton(button)) {
			return fail(context.getSource(), Failure.detail(FailureCode.NOT_BUTTON, "minecraft:stone_button"));
		}
		Optional<Binding> binding = railway.state().binding(RailWorld.dimension(level), button);
		if (binding.isEmpty()) {
			return fail(context.getSource(), Failure.at(FailureCode.BUTTON_NOT_BOUND, button));
		}
		Binding found = binding.get();
		String current = world.blockIdAt(button);
		CommandSourceStack source = context.getSource();
		source.sendSuccess(() -> Messages.text(source, "button.info", button.shortString(),
				found.fromStationId().value(), found.toStationId().value(), current), false);
		return 1;
	}

	private static int buttonUnbind(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		ServerPlayer player = player(context.getSource());
		if (railway == null || player == null) {
			return 0;
		}
		BlockLocation button = targetedBlock(player, context.getSource());
		if (button == null) {
			return 0;
		}
		ServerLevel level = player.level();
		Optional<Binding> removed = railway.state().removeBinding(RailWorld.dimension(level), button);
		if (removed.isEmpty()) {
			return fail(context.getSource(), Failure.at(FailureCode.BUTTON_NOT_BOUND, button));
		}
		railway.saveState();
		return ok(context.getSource(), "button.unbound");
	}

	// ---------------------------------------------------------------- network

	private static int networkScan(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "station"));
		if (station == null) {
			return 0;
		}
		if (station.isProvisional()) {
			return fail(context.getSource(), Failure.detail(FailureCode.STATION_INCOMPLETE, station.displayName()));
		}
		ServerLevel level = RailWorld.level(context.getSource().getServer(), station.dimension());
		if (level == null) {
			return fail(context.getSource(), Failure.detail(FailureCode.WORLD_NOT_LOADED, station.dimension()));
		}
		ScanService.StartResult result = railway.scans().start(station, level, railway.config(),
				context.getSource().getServer().getTickCount(), railway.state().revision());
		if (result instanceof ScanService.StartResult.Rejected rejected) {
			return fail(context.getSource(), rejected.failure());
		}
		String jobId = ((ScanService.StartResult.Started) result).jobId();
		return ok(context.getSource(), "scan.started", station.id().value(), jobId);
	}

	private static int networkStatusAll(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		List<ScanService.Status> statuses = railway.scans().all();
		CommandSourceStack source = context.getSource();
		if (statuses.isEmpty()) {
			source.sendSuccess(() -> Messages.text(source, "scan.status.none"), false);
			return 1;
		}
		for (ScanService.Status status : statuses) {
			sendScanStatus(source, status);
		}
		return 1;
	}

	private static int networkStatus(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station station = station(context.getSource(), StringArgumentType.getString(context, "station"));
		if (station == null) {
			return 0;
		}
		Optional<ScanService.Status> status = railway.scans().status(station.id());
		if (status.isEmpty()) {
			context.getSource().sendSuccess(() -> Messages.text(context.getSource(), "scan.status.missing",
					station.id().value()), false);
			return 1;
		}
		sendScanStatus(context.getSource(), status.get());
		return 1;
	}

	private static void sendScanStatus(CommandSourceStack source, ScanService.Status status) {
		String finalText = scanStatusText(Messages.lang(source), status);
		source.sendSuccess(() -> Component.literal(finalText), false);
	}

	private static String scanStatusText(Messages.Lang lang, ScanService.Status status) {
		String text;
		if (status.running()) {
			text = status.waiting()
					? Messages.format(lang, "scan.status.waiting", status.station().value(),
							status.pendingChunks(), status.rails())
					: Messages.format(lang, "scan.status.running", status.station().value(), status.rails());
		} else if (status.failure() != null) {
			text = Messages.format(lang, "scan.status.failed", status.station().value(),
					scanFailureText(lang, status.failure()));
		} else {
			text = Messages.format(lang, "scan.status.complete", status.station().value(),
					status.rails(), status.switches(), status.revision());
		}
		if (status.coveredBy() != null) {
			text += Messages.format(lang, "scan.status.covered", status.coveredBy().value());
		} else if (!status.covered().isEmpty()) {
			text += Messages.format(lang, "scan.status.covers", stationList(status.covered()));
		}
		return text;
	}

	private static String stationList(List<StationId> stations) {
		StringBuilder builder = new StringBuilder();
		for (StationId station : stations) {
			if (!builder.isEmpty()) {
				builder.append(", ");
			}
			builder.append(station.value());
		}
		return builder.toString();
	}

	/** A scan timeout is not a trip stop, so keep its wording scan-specific. */
	private static String scanFailureText(Messages.Lang lang, Failure failure) {
		if (failure.code() == FailureCode.CHUNKS_NOT_READY) {
			return Messages.format(lang, "scan.status.chunks_not_ready",
					failure.location() == null ? "-" : failure.location().shortString());
		}
		return Messages.failureText(lang, failure);
	}

	// ------------------------------------------------------------------ route

	private static int routeCheck(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		Station from = station(context.getSource(), StringArgumentType.getString(context, "from"));
		Station to = station(context.getSource(), StringArgumentType.getString(context, "to"));
		if (from == null || to == null) {
			return 0;
		}
		CommandSourceStack source = context.getSource();
		if (from.isProvisional()) {
			return fail(source, Failure.detail(FailureCode.STATION_INCOMPLETE, from.displayName()));
		}
		if (to.isProvisional()) {
			return fail(source, Failure.detail(FailureCode.DESTINATION_NOT_ANCHORED, to.displayName()));
		}
		if (!to.hasExit()) {
			return fail(source, Failure.detail(FailureCode.STATION_INCOMPLETE, to.displayName()));
		}
		// One scan covers its whole connected component. Reuse it when it already
		// contains the other endpoint, so a missing destination scan does not hide a
		// route that the source graph proves.
		RailGraph fromGraph = railway.scans().graph(from.id());
		RailGraph toGraph = railway.scans().graph(to.id());
		if (fromGraph != null && toGraph == null && fromGraph.rail(to.dock()) != null) {
			toGraph = fromGraph;
		}
		if (toGraph != null && fromGraph == null && toGraph.rail(from.dock()) != null) {
			fromGraph = toGraph;
		}
		if (fromGraph == null) {
			fromGraph = graphOrFail(source, railway, from);
			if (fromGraph == null) {
				return 0;
			}
		}
		if (toGraph == null) {
			toGraph = graphOrFail(source, railway, to);
			if (toGraph == null) {
				return 0;
			}
		}
		if (!fromGraph.componentKey().equals(toGraph.componentKey())) {
			return fail(source, Failure.of(FailureCode.NO_PATH));
		}
		RailGraphPlanner.Result result = RailGraphPlanner.plan(fromGraph, from.id(), to.id(),
				from.dock(), to.dock(), railway.config().maxRouteLength());
		if (result instanceof RailGraphPlanner.Result.Rejected rejected) {
			return fail(source, rejected.failure());
		}
		GraphRoutePlan plan = ((RailGraphPlanner.Result.Planned) result).plan();
		ServerLevel level = RailWorld.level(source.getServer(), from.dimension());
		if (level == null) {
			return fail(source, Failure.detail(FailureCode.WORLD_NOT_LOADED, from.dimension()));
		}
		RailWorld world = new RailWorld(level);
		Failure worldFailure = world.validateGraphRoute(plan);
		if (worldFailure != null) {
			return fail(source, worldFailure);
		}
		if (world.isLoaded(to.exit()) && !world.isSafeStand(to.exit())) {
			return fail(source, Failure.at(FailureCode.EXIT_UNSAFE, to.exit()));
		}
		return ok(source, "route.ok", from.id().value(), to.id().value(), Integer.toString(plan.length()),
				Integer.toString(fromGraph.rails().size()), Integer.toString(plan.switches().size()),
				plan.componentKey());
	}

	private static RailGraph graphOrFail(CommandSourceStack source, Railway railway, Station station) {
		ScanService scans = railway.scans();
		RailGraph graph = scans.graph(station.id());
		if (graph != null) {
			return graph;
		}
		if (scans.isRunning(station.id())) {
			fail(source, Failure.detail(FailureCode.SCAN_RUNNING, station.displayName()));
			return null;
		}
		// No graph and no running job: rebuild the derived graph in the
		// background so a route check after a restart needs no manual scan.
		ServerLevel level = RailWorld.level(source.getServer(), station.dimension());
		if (level != null) {
			ScanService.StartResult started = scans.autoStart(station, level, railway.config(),
					source.getServer().getTickCount(), railway.state().revision());
			if (started instanceof ScanService.StartResult.Started) {
				fail(source, ScanService.autoStartedFailure(station.displayName()));
				return null;
			}
			if (started instanceof ScanService.StartResult.Rejected rejected) {
				fail(source, rejected.failure());
				return null;
			}
		}
		Failure failure = scans.failure(station.id());
		fail(source, failure != null ? failure : Failure.detail(FailureCode.SCAN_INCOMPLETE, station.displayName()));
		return null;
	}

	// ------------------------------------------------------------------- trip

	private static int tripList(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		List<Trip> trips = railway.trips().activeTrips();
		CommandSourceStack source = context.getSource();
		if (trips.isEmpty()) {
			source.sendSuccess(() -> Messages.text(source, "trip.list.empty"), false);
			return 1;
		}
		long now = source.getServer().getTickCount();
		for (Trip trip : trips) {
			ServerPlayer player = source.getServer().getPlayerList().getPlayer(trip.playerUuid());
			String playerName = player == null ? trip.playerUuid().toString() : player.getPlainTextName();
			source.sendSuccess(() -> Messages.text(source, "trip.list.entry", trip.id().toString(), playerName,
					trip.plan().from().value(), trip.plan().to().value(), trip.phase().name(),
					Long.toString(now - trip.startedTick())), false);
		}
		return 1;
	}

	private static int tripAbort(CommandContext<CommandSourceStack> context) {
		Railway railway = railway(context.getSource());
		if (railway == null) {
			return 0;
		}
		String raw = StringArgumentType.getString(context, "tripId");
		TripId id;
		try {
			id = TripId.parse(raw);
		} catch (IllegalArgumentException e) {
			return fail(context.getSource(), Failure.detail(FailureCode.TRIP_NOT_FOUND, raw));
		}
		TripService.AbortResult result = railway.trips().abort(id);
		if (result instanceof TripService.AbortResult.NotFound) {
			return fail(context.getSource(), Failure.detail(FailureCode.TRIP_NOT_FOUND, raw));
		}
		if (result instanceof TripService.AbortResult.Holding) {
			return fail(context.getSource(), Failure.detail(FailureCode.TRIP_NOT_ABORTABLE, raw));
		}
		return ok(context.getSource(), "trip.abort.done", raw);
	}

	// ------------------------------------------------------------ diagnostics

	private static int diagnostics(CommandContext<CommandSourceStack> context) {
		return diagnostics(context, null);
	}

	private static int diagnosticsStation(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!Railway.isStarted()) {
			source.sendFailure(Component.literal("Railway is not initialized"));
			return 0;
		}
		String rawId = StringArgumentType.getString(context, "station");
		if (!StationId.isValid(rawId)) {
			return fail(source, Failure.detail(FailureCode.INVALID_ID, rawId));
		}
		StationId id = new StationId(rawId);
		if (Railway.get().state().station(id).isEmpty()) {
			return fail(source, Failure.detail(FailureCode.STATION_NOT_FOUND, rawId));
		}
		return diagnostics(context, id);
	}

	private static int diagnostics(CommandContext<CommandSourceStack> context, StationId focus) {
		CommandSourceStack source = context.getSource();
		if (!Railway.isStarted()) {
			source.sendFailure(Component.literal("Railway is not initialized"));
			return 0;
		}
		Messages.Lang lang = Messages.lang(source);
		for (String line : Railway.get().diagnostics().render(lang, focus)) {
			source.sendSuccess(() -> Component.literal(line), false);
		}
		return 1;
	}

	// ----------------------------------------------------------------- reload

	private static int reload(CommandContext<CommandSourceStack> context) {
		if (!Railway.isStarted()) {
			context.getSource().sendFailure(Component.literal("Railway is not initialized"));
			return 0;
		}
		Railway railway = Railway.get();
		List<String> errors = railway.reloadConfig();
		CommandSourceStack source = context.getSource();
		if (errors != null) {
			source.sendSuccess(() -> Messages.text(source, "reload.invalid"), false);
			for (String error : errors) {
				source.sendFailure(Component.literal(error));
			}
			return 0;
		}
		if (railway.disabled()) {
			return fail(source, Failure.detail(FailureCode.DISABLED, railway.disabledReason()));
		}
		return ok(source, "reload.ok");
	}

	// ----------------------------------------------------------------- helpers

	private static Railway railway(CommandSourceStack source) {
		if (!Railway.isStarted()) {
			source.sendFailure(Component.literal("Railway is not initialized"));
			return null;
		}
		Railway railway = Railway.get();
		if (railway.disabled()) {
			fail(source, Failure.detail(FailureCode.DISABLED, railway.disabledReason()));
			return null;
		}
		return railway;
	}

	private static ServerPlayer player(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			fail(source, Failure.of(FailureCode.NOT_PLAYER));
			return null;
		}
		return player;
	}

	private static Station station(CommandSourceStack source, String rawId) {
		if (!Railway.isStarted()) {
			source.sendFailure(Component.literal("Railway is not initialized"));
			return null;
		}
		if (!StationId.isValid(rawId)) {
			fail(source, Failure.detail(FailureCode.INVALID_ID, rawId));
			return null;
		}
		Optional<Station> station = Railway.get().state().station(new StationId(rawId));
		if (station.isEmpty()) {
			fail(source, Failure.detail(FailureCode.STATION_NOT_FOUND, rawId));
			return null;
		}
		return station.get();
	}

	private static BlockLocation targetedBlock(ServerPlayer player, CommandSourceStack source) {
		Optional<BlockLocation> target = PlayerActions.targetedBlock(player, TARGET_RANGE);
		if (target.isEmpty()) {
			fail(source, Failure.detail(FailureCode.NOT_HOLDING_RAIL, Long.toString((long) TARGET_RANGE)));
			return null;
		}
		return target.get();
	}

	private static Failure dockFailure(GraphRailView.Probe probe, BlockLocation dock) {
		if (probe instanceof GraphRailView.Probe.Unloaded) {
			return Failure.at(FailureCode.WORLD_NOT_LOADED, dock);
		}
		if (!(probe instanceof GraphRailView.Probe.Rail rail)) {
			return Failure.at(FailureCode.NO_RAIL, dock);
		}
		if (!rail.shape().isStraightHorizontal()) {
			return Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, dock);
		}
		if (rail.kind() == RailKind.DETECTOR || rail.kind() == RailKind.ACTIVATOR) {
			return Failure.at(FailureCode.UNSUPPORTED_RAIL, dock, rail.kind().name().toLowerCase(Locale.ROOT));
		}
		return null;
	}

	private static int ok(CommandSourceStack source, String key, Object... args) {
		source.sendSuccess(() -> Messages.text(source, key, args), false);
		return 1;
	}

	private static int fail(CommandSourceStack source, Failure failure) {
		source.sendFailure(Messages.failure(source, failure));
		return 0;
	}
}
