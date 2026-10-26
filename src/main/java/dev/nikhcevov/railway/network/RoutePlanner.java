package dev.nikhcevov.railway.network;

import dev.nikhcevov.railway.domain.BlockLocation;
import dev.nikhcevov.railway.domain.Failure;
import dev.nikhcevov.railway.domain.FailureCode;
import dev.nikhcevov.railway.domain.RailAxis;
import dev.nikhcevov.railway.domain.RoutePlan;
import dev.nikhcevov.railway.domain.Station;

/** Pure route planning for the straight-line MVP network. */
public final class RoutePlanner {
	private RoutePlanner() {
	}

	public sealed interface Result {
		record Planned(RoutePlan plan) implements Result {
		}

		record Rejected(Failure failure) implements Result {
		}
	}

	public static Result plan(Station from, Station to, LineScan fromLine, LineScan toLine, int maxRouteLength) {
		if (!from.enabled()) {
			return reject(Failure.detail(FailureCode.STATION_DISABLED, from.id().value()));
		}
		if (!to.enabled()) {
			return reject(Failure.detail(FailureCode.STATION_DISABLED, to.id().value()));
		}
		if (!from.dimension().equals(to.dimension())) {
			return reject(Failure.detail(FailureCode.DIMENSION_MISMATCH, from.dimension() + " != " + to.dimension()));
		}
		if (!fromLine.componentKey().equals(toLine.componentKey())) {
			return reject(Failure.of(FailureCode.NO_PATH));
		}
		if (!fromLine.contains(from.dock())) {
			return reject(Failure.at(FailureCode.DOCK_NOT_ON_LINE, from.dock(), from.id().value()));
		}
		int toIndex = toLine.indexOf(to.dock());
		if (toIndex < 0) {
			return reject(Failure.at(FailureCode.DOCK_NOT_ON_LINE, to.dock(), to.id().value()));
		}
		if (!to.hasExit()) {
			return reject(Failure.detail(FailureCode.STATION_INCOMPLETE, to.id().value()));
		}
		RailAxis axis = fromLine.axis();
		int fromCoord = coordinate(from.dock(), axis);
		int toCoord = coordinate(to.dock(), axis);
		int sign = Integer.compare(toCoord, fromCoord);
		if (sign == 0) {
			return reject(Failure.at(FailureCode.TOO_SHORT, to.dock()));
		}
		int arrivalRails = sign > 0 ? toIndex : toLine.size() - 1 - toIndex;
		if (arrivalRails < 2) {
			return reject(Failure.at(FailureCode.TOO_SHORT, to.dock(), Integer.toString(arrivalRails)));
		}
		int length = Math.abs(toCoord - fromCoord);
		if (length > maxRouteLength) {
			return reject(Failure.at(FailureCode.LIMIT_EXCEEDED, to.dock(), Integer.toString(maxRouteLength)));
		}
		RoutePlan plan = new RoutePlan(
				from.id(),
				to.id(),
				from.dimension(),
				axis,
				from.dock(),
				to.dock(),
				to.exit(),
				to.exitYaw(),
				sign,
				length,
				fromLine.componentKey()
		);
		return new Result.Planned(plan);
	}

	private static Result reject(Failure failure) {
		return new Result.Rejected(failure);
	}

	private static int coordinate(BlockLocation pos, RailAxis axis) {
		return axis == RailAxis.X ? pos.x() : pos.z();
	}
}
