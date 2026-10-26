package dev.nikhcevov.railway.domain;

/** Common identity of a planned trip route, independent of geometry details. */
public interface TripPlan {
	StationId from();

	StationId to();

	String componentKey();
}
