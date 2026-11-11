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
	public record CorridorSpan(String key, String direction, int startStep, int endStep) {
	}

	public record ExclusiveSpan(String key, int startStep, int endStep, BlockLocation switchPos) {
	}

	public record RouteSchedule(
			String departurePlatformKey,
			String arrivalPlatformKey,
			List<CorridorSpan> corridors,
			List<ExclusiveSpan> exclusiveSpans
	) {
	}

	private RouteResourcePlanner() {
	}

	public static List<String> derive(RailGraph graph, GraphRoutePlan plan, Set<BlockLocation> dockPositions) {
		String dimension = plan.dimension();
		Set<BlockLocation> docks = collectDocks(plan, dockPositions);
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
				resources.add(switchZoneKey(dimension, switchPos));
			}
		}

		List<Integer> boundaries = findBoundaries(graph, plan.steps(), docks);
		for (int b = 0; b + 1 < boundaries.size(); b++) {
			int start = boundaries.get(b);
			int end = boundaries.get(b + 1);
			if (end == start + 1) {
				continue;
			}
			String sectionKey = sectionKey(dimension, plan.steps(), start, end);
			if (sectionKey != null) {
				resources.add(sectionKey);
			}
		}

		List<String> ordered = new ArrayList<>(resources);
		ordered.sort(Comparator.comparingInt(RouteResourcePlanner::rank).thenComparing(Comparator.naturalOrder()));
		return List.copyOf(ordered);
	}

	/**
	 * Plans a step-indexed reservation schedule with directional corridors and
	 * exclusive {@code segmentLength}-block track segments, platforms, and switch
	 * conflict zones.
	 */
	public static RouteSchedule planSchedule(RailGraph graph, GraphRoutePlan plan,
			Set<BlockLocation> dockPositions, int segmentLength) {
		String dimension = plan.dimension();
		int effectiveSegmentLength = Math.max(4, segmentLength);
		List<RouteStep> steps = plan.steps();
		int last = steps.size() - 1;
		Set<BlockLocation> docks = collectDocks(plan, dockPositions);

		String departurePlatformKey = platformKey(dimension, plan.fromDock());
		String arrivalPlatformKey = platformKey(dimension, plan.toDock());
		List<ExclusiveSpan> exclusive = new ArrayList<>();
		List<CorridorSpan> corridors = new ArrayList<>();

		// Hold the departure dock only while the cart is at step 0 or 1; once it
		// reaches step 2 (2 blocks clear of the dock), another player may board.
		exclusive.add(new ExclusiveSpan(departurePlatformKey, 0, Math.min(1, last), null));
		for (int i = 1; i <= last; i++) {
			BlockLocation pos = steps.get(i).pos();
			if (docks.contains(pos)) {
				exclusive.add(new ExclusiveSpan(platformKey(dimension, pos),
						Math.max(0, i - 1), Math.min(last, i + 1), null));
			}
		}

		for (BlockLocation switchPos : graph.switches()) {
			Set<BlockLocation> adjacent = graph.adjacentRails(switchPos);
			int minStep = Integer.MAX_VALUE;
			int maxStep = -1;
			for (int i = 0; i <= last; i++) {
				BlockLocation pos = steps.get(i).pos();
				if (pos.equals(switchPos) || adjacent.contains(pos)) {
					minStep = Math.min(minStep, i);
					maxStep = Math.max(maxStep, i);
				}
			}
			if (maxStep >= 0) {
				exclusive.add(new ExclusiveSpan(switchZoneKey(dimension, switchPos),
						minStep, Math.min(last, maxStep + 1), switchPos));
			}
		}

		List<Integer> boundaries = findBoundaries(graph, steps, docks);
		for (int b = 0; b + 1 < boundaries.size(); b++) {
			int start = boundaries.get(b);
			int end = boundaries.get(b + 1);
			if (end == start + 1) {
				continue;
			}
			String secKey = sectionKey(dimension, steps, start, end);
			if (secKey == null) {
				continue;
			}
			BlockLocation startPos = steps.get(start).pos();
			BlockLocation endPos = steps.get(end).pos();
			boolean canonicalForward = BlockLocation.POSITION_ORDER.compare(startPos, endPos) <= 0;
			String direction = canonicalForward ? "F" : "R";
			corridors.add(new CorridorSpan(secKey, direction, start, end));

			int interiorCount = end - start - 1;
			for (int cStart = 0; cStart < interiorCount; cStart += effectiveSegmentLength) {
				int cEnd = Math.min(interiorCount - 1, cStart + effectiveSegmentLength - 1);
				int segStartStep = canonicalForward ? (start + 1 + cStart) : (end - 1 - cEnd);
				int segEndStep = canonicalForward ? (start + 1 + cEnd) : (end - 1 - cStart);
				String segKey = segmentKey(dimension, steps, segStartStep, segEndStep);
				exclusive.add(new ExclusiveSpan(segKey, segStartStep, Math.min(last, segEndStep + 1), null));
			}
		}

		exclusive.sort(Comparator.comparingInt(ExclusiveSpan::startStep)
				.thenComparingInt(ExclusiveSpan::endStep)
				.thenComparingInt(span -> rank(span.key()))
				.thenComparing(ExclusiveSpan::key));
		return new RouteSchedule(departurePlatformKey, arrivalPlatformKey,
				List.copyOf(corridors), List.copyOf(exclusive));
	}

	private static Set<BlockLocation> collectDocks(GraphRoutePlan plan, Set<BlockLocation> dockPositions) {
		Set<BlockLocation> docks = new LinkedHashSet<>();
		for (BlockLocation dock : dockPositions) {
			if (dock != null) {
				docks.add(dock);
			}
		}
		docks.add(plan.fromDock());
		docks.add(plan.toDock());
		return docks;
	}

	private static List<Integer> findBoundaries(RailGraph graph, List<RouteStep> steps, Set<BlockLocation> docks) {
		List<Integer> boundaries = new ArrayList<>();
		for (int i = 0; i < steps.size(); i++) {
			BlockLocation pos = steps.get(i).pos();
			GraphRail rail = graph.rail(pos);
			if (docks.contains(pos) || (rail != null && rail.isSwitch())) {
				boundaries.add(i);
			}
		}
		return boundaries;
	}

	private static String sectionKey(String dimension, List<RouteStep> steps, int start, int end) {
		List<BlockLocation> interior = new ArrayList<>();
		for (int i = start + 1; i < end; i++) {
			interior.add(steps.get(i).pos());
		}
		if (interior.isEmpty()) {
			return null;
		}
		interior.sort(BlockLocation.POSITION_ORDER);
		int hash = 1;
		for (BlockLocation pos : interior) {
			hash = 31 * hash + pos.hashCode();
		}
		BlockLocation a = steps.get(start).pos();
		BlockLocation z = steps.get(end).pos();
		if (BlockLocation.POSITION_ORDER.compare(z, a) < 0) {
			BlockLocation swap = a;
			a = z;
			z = swap;
		}
		return "sec|" + dimension + '|' + a.x() + ',' + a.y() + ',' + a.z()
				+ '|' + z.x() + ',' + z.y() + ',' + z.z()
				+ '|' + interior.size() + '|' + Integer.toHexString(hash);
	}

	private static String segmentKey(String dimension, List<RouteStep> steps, int startStep, int endStep) {
		List<BlockLocation> positions = new ArrayList<>(endStep - startStep + 1);
		for (int i = startStep; i <= endStep; i++) {
			positions.add(steps.get(i).pos());
		}
		positions.sort(BlockLocation.POSITION_ORDER);
		int hash = 1;
		for (BlockLocation pos : positions) {
			hash = 31 * hash + pos.hashCode();
		}
		BlockLocation a = positions.getFirst();
		BlockLocation z = positions.getLast();
		return "seg|" + dimension + '|' + a.x() + ',' + a.y() + ',' + a.z()
				+ '|' + z.x() + ',' + z.y() + ',' + z.z()
				+ '|' + positions.size() + '|' + Integer.toHexString(hash);
	}

	private static boolean intersects(Set<BlockLocation> a, Set<BlockLocation> b) {
		for (BlockLocation pos : a) {
			if (b.contains(pos)) {
				return true;
			}
		}
		return false;
	}

	public static String platformKey(String dimension, BlockLocation dock) {
		return "plat|" + dimension + '|' + dock.x() + ',' + dock.y() + ',' + dock.z();
	}

	public static String switchZoneKey(String dimension, BlockLocation switchPos) {
		return "swz|" + dimension + '|' + switchPos.x() + ',' + switchPos.y() + ',' + switchPos.z();
	}

	private static int rank(String key) {
		if (key.startsWith("plat|")) {
			return 0;
		}
		if (key.startsWith("sec|") || key.startsWith("seg|")) {
			return 1;
		}
		return 2;
	}
}
