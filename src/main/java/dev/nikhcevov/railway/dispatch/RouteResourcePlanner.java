package dev.nikhcevov.railway.dispatch;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.network.GraphRail;
import dev.nikhcevov.railway.network.GraphRoutePlan;
import dev.nikhcevov.railway.network.RailGraph;
import dev.nikhcevov.railway.network.RouteStep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives exclusive resources for a planned graph route: station platforms,
 * maximal sections between platforms/switches, and switch conflict zones.
 * Keys are runtime-only, deterministic and direction-independent.
 */
public final class RouteResourcePlanner {
	private static final Comparator<BlockLocation> POSITION_ORDER =
			Comparator.comparingInt(BlockLocation::x)
					.thenComparingInt(BlockLocation::y)
					.thenComparingInt(BlockLocation::z);

	private RouteResourcePlanner() {
	}

	public static List<String> derive(RailGraph graph, GraphRoutePlan plan, Set<BlockLocation> dockPositions) {
		String dimension = plan.dimension();
		Set<BlockLocation> docks = new LinkedHashSet<>(dockPositions);
		docks.add(plan.fromDock());
		docks.add(plan.toDock());
		Set<String> resources = new LinkedHashSet<>();
		Set<BlockLocation> routePositions = new LinkedHashSet<>();

		for (RouteStep step : plan.steps()) {
			routePositions.add(step.pos());
			if (docks.contains(step.pos())) {
				resources.add(platformKey(dimension, step.pos()));
			}
		}

		for (BlockLocation switchPos : graph.switches()) {
			if (routePositions.contains(switchPos)
					|| intersects(graph.adjacentRails(switchPos), routePositions)) {
				resources.add("swz|" + dimension + '|' + switchPos.x() + ',' + switchPos.y() + ',' + switchPos.z());
			}
		}

		List<Integer> boundaries = new ArrayList<>();
		for (int i = 0; i < plan.steps().size(); i++) {
			BlockLocation pos = plan.steps().get(i).pos();
			GraphRail rail = graph.rail(pos);
			if (docks.contains(pos) || (rail != null && rail.isSwitch())) {
				boundaries.add(i);
			}
		}
		for (int b = 0; b + 1 < boundaries.size(); b++) {
			int start = boundaries.get(b);
			int end = boundaries.get(b + 1);
			if (end == start + 1) {
				continue;
			}
			List<BlockLocation> interior = new ArrayList<>();
			for (int i = start + 1; i < end; i++) {
				interior.add(plan.steps().get(i).pos());
			}
			if (interior.isEmpty()) {
				continue;
			}
			interior.sort(POSITION_ORDER);
			int hash = 1;
			for (BlockLocation pos : interior) {
				hash = 31 * hash + pos.hashCode();
			}
			BlockLocation a = plan.steps().get(start).pos();
			BlockLocation z = plan.steps().get(end).pos();
			if (POSITION_ORDER.compare(z, a) < 0) {
				BlockLocation swap = a;
				a = z;
				z = swap;
			}
			resources.add("sec|" + dimension + '|' + a.x() + ',' + a.y() + ',' + a.z()
					+ '|' + z.x() + ',' + z.y() + ',' + z.z()
					+ '|' + interior.size() + '|' + Integer.toHexString(hash));
		}

		List<String> ordered = new ArrayList<>(resources);
		ordered.sort(Comparator.comparingInt(RouteResourcePlanner::rank).thenComparing(Comparator.naturalOrder()));
		return List.copyOf(ordered);
	}

	private static boolean intersects(Set<BlockLocation> a, Set<BlockLocation> b) {
		for (BlockLocation pos : a) {
			if (b.contains(pos)) {
				return true;
			}
		}
		return false;
	}

	private static String platformKey(String dimension, BlockLocation dock) {
		return "plat|" + dimension + '|' + dock.x() + ',' + dock.y() + ',' + dock.z();
	}

	private static int rank(String key) {
		if (key.startsWith("plat|")) {
			return 0;
		}
		if (key.startsWith("sec|")) {
			return 1;
		}
		return 2;
	}
}
