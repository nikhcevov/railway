package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.RailAxis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tick-batched scanner for the 1.0 MVP case: one straight horizontal line through
 * the dock. Any curve, slope, unsupported rail type or unloaded chunk fails the scan
 * with coordinates instead of silently producing a partial route.
 */
public final class StraightLineScanner {
	private final String dimension;
	private final BlockLocation dock;
	private final RailView view;
	private final int maxRails;

	private final List<BlockLocation> negative = new ArrayList<>();
	private final List<BlockLocation> positive = new ArrayList<>();
	private RailAxis axis;
	private int direction;
	private BlockLocation cursor;
	private int railCount;
	private int poweredCount;
	private boolean initialized;
	private boolean finished;
	private Failure failure;
	private LineScan result;

	public StraightLineScanner(String dimension, BlockLocation dock, RailView view, int maxRails) {
		this.dimension = dimension;
		this.dock = dock;
		this.view = view;
		this.maxRails = maxRails;
	}

	/**
	 * Probes at most {@code probeBudget} blocks or until the nano deadline.
	 * Returns true when the scan reached a final state.
	 */
	public boolean advance(int probeBudget, long deadlineNanos) {
		int probes = 0;
		while (!finished && probes < probeBudget) {
			if (probes > 0 && System.nanoTime() > deadlineNanos) {
				break;
			}
			step();
			probes++;
		}
		return finished;
	}

	private void step() {
		if (!initialized) {
			initialize();
			return;
		}

		BlockLocation next = cursor.offset(
				axis == RailAxis.X ? direction : 0,
				0,
				axis == RailAxis.Z ? direction : 0
		);
		RailProbe probe = view.probe(next);
		switch (probe) {
			case RailProbe.Unloaded ignored -> fail(Failure.at(FailureCode.SCAN_INCOMPLETE, next));
			case RailProbe.Empty ignored -> finishDirection();
			case RailProbe.Rail rail -> accept(next, rail.info());
		}
	}

	private void initialize() {
		initialized = true;
		RailProbe probe = view.probe(dock);
		switch (probe) {
			case RailProbe.Unloaded ignored -> {
				fail(Failure.at(FailureCode.SCAN_INCOMPLETE, dock));
				return;
			}
			case RailProbe.Empty ignored -> {
				fail(Failure.at(FailureCode.NO_RAIL, dock));
				return;
			}
			case RailProbe.Rail rail -> {
				RailInfo info = rail.info();
				if (info.slope() || !info.straight()) {
					fail(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, dock));
					return;
				}
				if (info.kind() == RailKind.DETECTOR || info.kind() == RailKind.ACTIVATOR) {
					fail(Failure.at(FailureCode.UNSUPPORTED_RAIL, dock, info.kind().name()));
					return;
				}
				axis = info.axis();
				if (axis == null) {
					fail(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, dock));
					return;
				}
				direction = 1;
				cursor = dock;
				railCount = 1;
				poweredCount = info.kind() == RailKind.POWERED ? 1 : 0;
			}
		}
	}

	private void accept(BlockLocation pos, RailInfo info) {
		if (info.slope() || !info.straight() || info.axis() != axis) {
			fail(Failure.at(FailureCode.UNSUPPORTED_GEOMETRY, pos));
			return;
		}
		if (info.kind() == RailKind.DETECTOR || info.kind() == RailKind.ACTIVATOR) {
			fail(Failure.at(FailureCode.UNSUPPORTED_RAIL, pos, info.kind().name()));
			return;
		}
		if (railCount + 1 > maxRails) {
			fail(Failure.at(FailureCode.LIMIT_EXCEEDED, pos, Integer.toString(maxRails)));
			return;
		}
		(direction > 0 ? positive : negative).add(pos);
		railCount++;
		if (info.kind() == RailKind.POWERED) {
			poweredCount++;
		}
		cursor = pos;
	}

	private void finishDirection() {
		if (direction > 0) {
			direction = -1;
			cursor = dock;
			return;
		}
		complete();
	}

	private void complete() {
		List<BlockLocation> ordered = new ArrayList<>(negative.size() + positive.size() + 1);
		List<BlockLocation> reversed = new ArrayList<>(negative);
		Collections.reverse(reversed);
		ordered.addAll(reversed);
		ordered.add(dock);
		ordered.addAll(positive);
		result = new LineScan(dimension, axis, ordered, negative.size(), poweredCount);
		finished = true;
	}

	private void fail(Failure value) {
		failure = value;
		finished = true;
	}

	public boolean isFinished() {
		return finished;
	}

	public boolean isFailed() {
		return failure != null;
	}

	public Failure failure() {
		return failure;
	}

	public LineScan result() {
		if (result == null) {
			throw new IllegalStateException("scan not complete");
		}
		return result;
	}

	public int railCount() {
		return railCount;
	}

	public RailAxis axis() {
		return axis;
	}
}
