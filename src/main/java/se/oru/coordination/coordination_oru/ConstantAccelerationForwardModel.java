package se.oru.coordination.coordination_oru;

import org.metacsp.multi.spatioTemporal.paths.TrajectoryEnvelope;

import se.oru.coordination.coordination_oru.simulation2D.State;

public class ConstantAccelerationForwardModel implements ForwardModel {
		
	private double maxAccel, maxVel;
	private int CONTROL_PERIOD = -1;
	private double TEMPORAL_RESOLUTION = -1;
	private int numControlPeriodsFreeRun = 1;

	@Deprecated
	public ConstantAccelerationForwardModel(double maxAccel, double maxVel) {
		this.maxAccel = maxAccel;
		this.maxVel = maxVel;	
	}
	
	public ConstantAccelerationForwardModel(double maxAccel, double maxVel, int CONTROL_PERIOD, double TEMPORAL_RESOLUTION, int numControlPeriodsFreeRun) {
		this.maxAccel = maxAccel;
		this.maxVel = maxVel;	
		this.CONTROL_PERIOD = CONTROL_PERIOD;
		this.TEMPORAL_RESOLUTION = TEMPORAL_RESOLUTION;
		this.numControlPeriodsFreeRun = numControlPeriodsFreeRun;
	}

	public ConstantAccelerationForwardModel(double maxAccel, double maxVel, int CONTROL_PERIOD, double TEMPORAL_RESOLUTION) {
		this(maxAccel,maxVel,CONTROL_PERIOD,TEMPORAL_RESOLUTION,1);
	}

	@Override
	public boolean canStop(TrajectoryEnvelope te, RobotReport currentState, int targetPathIndex) {
		double distance = se.oru.coordination.coordination_oru.simulation2D.TrajectoryEnvelopeTrackerRK4.computeDistance(te.getTrajectory(), (currentState.getPathIndex() != -1 ? currentState.getPathIndex() : 0), targetPathIndex);
		State state = new State(0.0, currentState.getVelocity());
		double time = 0.0;
		double deltaTime = 0.0001;
		if (CONTROL_PERIOD != -1) {
			while (time*TEMPORAL_RESOLUTION < Math.max(CONTROL_PERIOD*numControlPeriodsFreeRun,TrajectoryEnvelopeCoordinator.EFFECTIVE_CONTROL_PERIOD)) {
				se.oru.coordination.coordination_oru.simulation2D.TrajectoryEnvelopeTrackerRK4.integrateRK4(state, time, deltaTime, false, maxVel, 1.0, maxAccel*1.1);
				time += deltaTime;
			}
		}
		while (state.getVelocity() > 0) {
			if (state.getPosition() > distance) return false;
			se.oru.coordination.coordination_oru.simulation2D.TrajectoryEnvelopeTrackerRK4.integrateRK4(state, time, deltaTime, true, maxVel, 1.0, maxAccel*0.9);
			time += deltaTime;
		}
		return true;
	}

}
