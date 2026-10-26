package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.RailAxis;

import java.util.List;

/** Ordered straight horizontal rail line. Rails are sorted by increasing coordinate on {@code axis}. */
public record LineScan(String dimension, RailAxis axis, List<BlockLocation> rails, int dockIndex, int poweredCount) {
	public LineScan {
		rails = List.copyOf(rails);
		if (dockIndex < 0 || dockIndex >= rails.size()) {
			throw new IllegalArgumentException("dockIndex outside line");
		}
	}

	public BlockLocation dock() {
		return rails.get(dockIndex);
	}

	public BlockLocation first() {
		return rails.getFirst();
	}

	public BlockLocation last() {
		return rails.getLast();
	}

	public int size() {
		return rails.size();
	}

	public double length() {
		BlockLocation a = first();
		BlockLocation b = last();
		return Math.abs((axis == RailAxis.X ? b.x() - a.x() : b.z() - a.z()));
	}

	public int indexOf(BlockLocation pos) {
		return rails.indexOf(pos);
	}

	public String componentKey() {
		BlockLocation a = first();
		BlockLocation b = last();
		return dimension + '|' + axis + '|' + a.x() + ',' + a.y() + ',' + a.z() + '|' + b.x() + ',' + b.y() + ',' + b.z();
	}

	public boolean contains(BlockLocation pos) {
		return rails.contains(pos);
	}
}
