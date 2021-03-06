package se.oru.coordination.coordination_oru;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

import javax.swing.SwingUtilities;

import org.apache.commons.collections.comparators.ComparatorChain;
import org.jgrapht.alg.cycle.JohnsonSimpleCycles;
import org.jgrapht.graph.DirectedMultigraph;
import org.metacsp.framework.Constraint;
import org.metacsp.meta.spatioTemporal.paths.Map;
import org.metacsp.multi.allenInterval.AllenIntervalConstraint;
import org.metacsp.multi.spatial.DE9IM.GeometricShapeDomain;
import org.metacsp.multi.spatial.DE9IM.GeometricShapeVariable;
import org.metacsp.multi.spatioTemporal.paths.Pose;
import org.metacsp.multi.spatioTemporal.paths.PoseSteering;
import org.metacsp.multi.spatioTemporal.paths.TrajectoryEnvelope;
import org.metacsp.multi.spatioTemporal.paths.TrajectoryEnvelopeSolver;
import org.metacsp.time.Bounds;
import org.metacsp.utility.UI.Callback;
import org.metacsp.utility.logging.MetaCSPLogging;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

import se.oru.coordination.coordination_oru.util.FleetVisualization;
import se.oru.coordination.coordination_oru.util.StringUtils;

/**
 * This class provides coordination for a fleet of robots. An instantiatable {@link TrajectoryEnvelopeCoordinator}
 * must provide an implementation of a time keeping method, a {@link TrajectoryEnvelope} tracker factory, and
 * a criteria with which robots are to be prioritized.
 * 
 * @author fpa
 *
 */
public abstract class TrajectoryEnvelopeCoordinator {

	public static String TITLE = "coordination_oru - Online coordination for multiple robots";
	public static String COPYRIGHT = "Copyright (C) 2017 Federico Pecora";

	//null -> public (GPL3) license
	public static String LICENSE = null;

	public static String PUBLIC_LICENSE = "This program comes with ABSOLUTELY NO WARRANTY. "
			+ "This program is free software: you can redistribute it and/or modify it under the "
			+ "terms of the GNU General Public License as published by the Free Software Foundation, "
			+ "either version 3 of the License, or (at your option) any later version. see LICENSE for details.";
	public static String PRIVATE_LICENSE = "This program comes with ABSOLUTELY NO WARRANTY. "
			+ "This program has been licensed to " + LICENSE + ". The licensee may "
			+ "redistribute it under certain conditions; see LICENSE for details.";

	//Force printing of (c) and license upon class loading
	static { printLicense(); }

	public static final int PARKING_DURATION = 3000;
	protected static final int DEFAULT_STOPPING_TIME = 5000;
	protected int CONTROL_PERIOD;
	protected double TEMPORAL_RESOLUTION;

	public static int EFFECTIVE_CONTROL_PERIOD = 0;

	protected boolean overlay = false;
	protected boolean quiet = false;

	protected TrajectoryEnvelopeSolver solver = null;
	//protected JTSDrawingPanel panel = null;
	protected FleetVisualization viz = null;
	protected ArrayList<TrajectoryEnvelope> envelopesToTrack = new ArrayList<TrajectoryEnvelope>();
	protected ArrayList<TrajectoryEnvelope> currentParkingEnvelopes = new ArrayList<TrajectoryEnvelope>();
	protected HashMap<CriticalSection,Dependency> criticalSectionsToDeps = new HashMap<CriticalSection, Dependency>();
	protected ArrayList<CriticalSection> allCriticalSections = new ArrayList<CriticalSection>();
	protected HashMap<Integer,ArrayList<Integer>> stoppingPoints = new HashMap<Integer,ArrayList<Integer>>();
	protected HashMap<Integer,ArrayList<Integer>> stoppingTimes = new HashMap<Integer,ArrayList<Integer>>();
	protected HashMap<Integer,Thread> stoppingPointTimers = new HashMap<Integer,Thread>();

	protected HashMap<Integer,AbstractTrajectoryEnvelopeTracker> trackers = new HashMap<Integer, AbstractTrajectoryEnvelopeTracker>();
	protected HashSet<Dependency> currentDependencies = new HashSet<Dependency>();

	protected Logger metaCSPLogger = MetaCSPLogging.getLogger(TrajectoryEnvelopeCoordinator.class);
	protected String logDirName = null;

	protected HashMap<AbstractTrajectoryEnvelopeTracker,Integer> communicatedCPs = new HashMap<AbstractTrajectoryEnvelopeTracker, Integer>();

	protected Map mapMetaConstraint = null;

	protected ComparatorChain comparators = new ComparatorChain();
	protected HashMap<Integer,ForwardModel> forwardModels = new HashMap<Integer, ForwardModel>();

	protected HashMap<Integer,Coordinate[]> footprints = new HashMap<Integer, Coordinate[]>();
	protected HashMap<Integer,Double> maxFootprintDimensions = new HashMap<Integer, Double>();

	protected HashSet<Dependency> disallowedDependencies = new HashSet<Dependency>();

	protected HashSet<Integer> muted = new HashSet<Integer>();

	protected boolean yieldIfParking = true;
	protected boolean checkEscapePoses = true;
	protected boolean breakDeadlocks = true;

	protected HashMap<Integer,TrackingCallback> trackingCallbacks = new HashMap<Integer, TrackingCallback>();
	
	protected Callback inferenceCallback = null;
	
	//Default footprint (same for all robots)
	//NOTE: coordinates must be in CCW or CW order
	public static Coordinate[] DEFAULT_FOOTPRINT = new Coordinate[] {
			new Coordinate(-1.7, 0.7),	//back left
			new Coordinate(-1.7, -0.7),	//back right
			new Coordinate(2.7, -0.7),	//front right
			new Coordinate(2.7, 0.7)	//front left
	};

	//Reflects the default footprint
	public static double MAX_DEFAULT_FOOTPRINT_DIMENSION = 4.4;

	/**
	 * Set whether this {@link TrajectoryEnvelopeCoordinator} should print info at every period.
	 * @param value Set to <code>true</code> if this {@link TrajectoryEnvelopeCoordinator} should print info at every period.
	 */
	public void setQuiet(boolean value) {
		this.quiet = value;
	}
	/**
	 * Set a {@link Callback} that will be called at every cycle.
	 * @param cb A {@link Callback} that will be called at every cycle.
	 */
	public void setInferenceCallback(Callback cb) {
		this.inferenceCallback = cb;
	}
	
	/**
	 * Get the control period of this {@link TrajectoryEnvelopeCoordinator}.
	 * @return the control period (in milliseconds) of this {@link TrajectoryEnvelopeCoordinator}.
	 */
	public int getControlPeriod() {
		return this.CONTROL_PERIOD;
	}

	/**
	 * Set whether the coordinator should try to break deadlocks by disallowing an arbitrary
	 * ordering involved in a loop in the dependency multigraph.
	 * @param value <code>true</code> deadlocks should be broken.
	 */
	public void setBreakDeadlocks(boolean value) {
		this.breakDeadlocks = value;
	}

	/**
	 * Set whether robots that will park in a critical section should yield to other robots.
	 * @param value <code>true</code> if robots that will park in a critical section should yield to other robots.
	 */
	public void setYieldIfParking(boolean value) {
		this.yieldIfParking = value;
	}

	/**
	 * Set whether completely overlapping paths should lead to failure.
	 * @param value <code>true</code> if completely overlapping paths should lead to failure.
	 */
	public void setCheckEscapePoses(boolean value) {
		this.checkEscapePoses = value;
	}

	/**
	 * Toggle mute/unmute communication with a given robot. 
	 * @param robotID The robot to toggle mute/unmute communication with.
	 */
	public void toggleMute(int robotID) {
		if (muted.contains(robotID)) muted.remove(robotID);
		else muted.add(robotID);
	}

	/**
	 * Mute communication with a given robot. 
	 * @param robotID The robot to mute communication with.
	 */
	public void mute(int robotID) {
		muted.add(robotID);
	}

	/**
	 * Get the IDs of robots that are muted.
	 * @return The IDs of robots that are muted.
	 */
	public int[] getMuted() {
		int[] ret = new int[muted.size()];
		int counter = 0;
		for (Integer m : muted) ret[counter++] = m; 
		return ret;
	}

	/**
	 * Unmute communication with a given robot. 
	 * @param robotID The robot to unmute communication with.
	 */
	public void unMute(int robotID) {
		muted.remove(robotID);
	}

	public double getMaxFootprintDimension(int robotID) {
		if (this.footprints.containsKey(robotID)) return maxFootprintDimensions.get(robotID);
		return MAX_DEFAULT_FOOTPRINT_DIMENSION;
	}

	/**
	 * Get the {@link Coordinate}s defining the default footprint of robots.
	 * @return The {@link Coordinate}s defining the default footprint of robots.
	 */
	public Coordinate[] getDefaultFootprint() {
		return DEFAULT_FOOTPRINT;
	}

	/**
	 * Get the {@link Coordinate}s defining the footprint of a given robot.
	 * @param robotID the ID of the robot
	 * @return The {@link Coordinate}s defining the footprint of a given robot.
	 */
	public Coordinate[] getFootprint(int robotID) {
		if (this.footprints.containsKey(robotID)) return this.footprints.get(robotID);
		return DEFAULT_FOOTPRINT;
	}

	/**
	 * Get a {@link Geometry} representing the default footprint of robots.
	 * @return A {@link Geometry} representing the default footprint of robots.
	 */
	public Geometry getDefaultFootprintPolygon() {
		Geometry fpGeom = TrajectoryEnvelope.createFootprintPolygon(DEFAULT_FOOTPRINT);
		return fpGeom;
	}

	/**
	 * Get a {@link Geometry} representing the footprint of a given robot.
	 * @param robotID the ID of the robot
	 * @return A {@link Geometry} representing the footprint of a given robot.
	 */
	public Geometry getFootprintPolygon(int robotID) {
		Geometry fpGeom = TrajectoryEnvelope.createFootprintPolygon(getFootprint(robotID));
		return fpGeom;
	}

	/**
	 * Set the {@link ForwardModel} of a given robot.
	 * @param robotID The ID of the robot.
	 * @param fm The robot's {@link ForwardModel}.
	 */
	public void setForwardModel(int robotID, ForwardModel fm) {
		this.forwardModels.put(robotID, fm);
	}

	/**
	 * Get the {@link ForwardModel} of a given robot.
	 * @param robotID The ID of the robot.
	 * @return The {@link ForwardModel} of the robot.
	 */
	public ForwardModel getForwardModel(int robotID) {
		if (forwardModels.containsKey(robotID)) return forwardModels.get(robotID);
		System.out.println("Returning default FM for " + robotID);
		return new ForwardModel() {
			@Override
			public boolean canStop(TrajectoryEnvelope te, RobotReport currentState, int targetPathIndex) {
				return true;
			}
		};
	}

	/**
	 * Create a new {@link TrajectoryEnvelopeCoordinator}, with control period 1000 msec,
	 * and temporal resolution 1000 (milliseconds).
	 */
	public TrajectoryEnvelopeCoordinator() {
		this(1000, 1000.0);
	}

	/**
	 * Create a new {@link TrajectoryEnvelopeCoordinator}, with given control period and temporal resolution.
	 * @param CONTROL_PERIOD The control period of the coordinator, e.g., 1000 msec.
	 * @param TEMPORAL_RESOLUTION The temporal resolution of the control period, e.g., 1000 for milliseconds.
	 */
	public TrajectoryEnvelopeCoordinator(int CONTROL_PERIOD, double TEMPORAL_RESOLUTION) {
		this.CONTROL_PERIOD = CONTROL_PERIOD;
		this.TEMPORAL_RESOLUTION = TEMPORAL_RESOLUTION;
		//FOR PRINTING
		if (overlay) {
			//clear screen
			System.out.printf(((char) 0x1b) + "[2J");
			//make window from row 8 to bottom (200)
			System.out.printf(((char) 0x1b) + "[8;200r");
		}
		setupLogging();
	}

	protected void setupLogging() {
		//logDirName = "log-" + Calendar.getInstance().getTimeInMillis();
		logDirName = "logs";
		File dir = new File(logDirName);
		dir.mkdir();
		MetaCSPLogging.setLogDir(logDirName);
	}

	public TrajectoryEnvelopeSolver getSolver() {
		return this.solver;
	}

	/**
	 * Instruct a given robot's tracker that it may not navigate beyond a given 
	 * path index.  
	 * @param robotID The ID of the robot.
	 * @param criticalPoint The index of the path pose beyond which the robot should not navigate.
	 */
	public void setCriticalPoint(int robotID, int criticalPoint) {
		//If the robot is not muted
		if (!muted.contains(robotID)) {
			//If not at the end of the trajectory (i.e., if the mission is not about to finish)
			if (trackers.get(robotID).getRobotReport().getPathIndex() < trackers.get(robotID).getTrajectoryEnvelope().getSequenceNumberEnd()) {
				//If I haven't communicated this CP already to the robot
				if (!communicatedCPs.containsKey(trackers.get(robotID)) || !communicatedCPs.get(trackers.get(robotID)).equals(criticalPoint) ) {
					communicatedCPs.put(trackers.get(robotID), criticalPoint);
					trackers.get(robotID).setCriticalPoint(criticalPoint);
				}
			}
		}
	}

	/**
	 * Get the current state of a given robot.
	 * @param robotID The ID of the robot of which the state should be returned.
	 * @return The current state of a given robot.
	 */
	public RobotReport getRobotReport(int robotID) {
		return trackers.get(robotID).getRobotReport();
	}

	/**
	 * Set the default footprint of robots, which is used for computing spatial envelopes.
	 * Provide the bounding polygon of the machine assuming its reference point is in (0,0), and its
	 * orientation is aligned with the x-axis. The coordinates must be in CW or CCW order.
	 * @param coordinates The coordinates delimiting bounding polygon of the footprint.
	 */
	public void setDefaultFootprint(Coordinate ... coordinates) {
		DEFAULT_FOOTPRINT = coordinates;
		MAX_DEFAULT_FOOTPRINT_DIMENSION = computeMaxFootprintDimension(coordinates);
	}


	/**
	 * Set the default footprint of robots, which is used for computing spatial envelopes.
	 * Provide the bounding polygon of the machine assuming its reference point is in (0,0), and its
	 * orientation is aligned with the x-axis. The coordinates must be in CW or CCW order.
	 * @param coordinates The coordinates delimiting bounding polygon of the footprint.
	 */
	@Deprecated
	public void setFootprint(Coordinate ... coordinates) {
		DEFAULT_FOOTPRINT = coordinates;
		MAX_DEFAULT_FOOTPRINT_DIMENSION = computeMaxFootprintDimension(coordinates);
	}

	/**
	 * Set the footprint of a given robot, which is used for computing spatial envelopes.
	 * Provide the bounding polygon of the machine assuming its reference point is in (0,0), and its
	 * orientation is aligned with the x-axis. The coordinates must be in CW or CCW order.
	 * @param robotID The ID of the robot.
	 * @param coordinates The coordinates delimiting bounding polygon of the footprint.
	 */
	public void setFootprint(int robotID, Coordinate ... coordinates) {
		this.footprints.put(robotID, coordinates);
		maxFootprintDimensions.put(robotID,computeMaxFootprintDimension(coordinates));
	}

	private double computeMaxFootprintDimension(Coordinate[] coords) {
		ArrayList<Double> fpX = new ArrayList<Double>();
		ArrayList<Double> fpY = new ArrayList<Double>();
		for (Coordinate coord : coords) {
			fpX.add(coord.x);
			fpY.add(coord.y);
		}
		Collections.sort(fpX);
		Collections.sort(fpY);
		return Math.max(fpX.get(fpX.size()-1)-fpX.get(0), fpY.get(fpY.size()-1)-fpY.get(0));
	}

	/**
	 * Call this method to setup the solvers that manage the {@link TrajectoryEnvelope} representation
	 * underlying the coordinator.
	 * @param origin The origin of time (milliseconds).
	 * @param horizon The maximum time (milliseconds).
	 */
	public void setupSolver(long origin, long horizon) {

		//Create meta solver and solver
		solver = new TrajectoryEnvelopeSolver(origin, horizon);

		//Start a thread that checks and enforces dependencies at every clock tick
		this.setupInferenceCallback();

	}

	/**
	 * Get the current time of the system, in milliseconds.
	 * @return The current time of the system, in milliseconds.
	 */
	public abstract long getCurrentTimeInMillis(); 

	/**
	 * Place a robot with a given ID in a given {@link Pose}.
	 * @param robotID The ID of the robot.
	 * @param currentPose The {@link Pose} in which to place the robot.
	 */
	public void placeRobot(final int robotID, Pose currentPose) {
		this.placeRobot(robotID, currentPose, null, currentPose.toString());
	}

	/**
	 * Place a robot with a given ID in the first {@link Pose} of a given {@link TrajectoryEnvelope}.
	 * @param robotID The ID of the robot.
	 * @param parking The {@link TrajectoryEnvelope} in whci the robot is parked.
	 */
	public void placeRobot(final int robotID, TrajectoryEnvelope parking) {
		this.placeRobot(robotID, null, parking, null);
	}

	/**
	 * Place a robot with a given ID in a given {@link Pose} of a given {@link TrajectoryEnvelope},
	 * labeled with a given string.
	 * @param robotID The ID of the robot.
	 * @param currentPose The {@link Pose} of the robot.
	 * @param parking The {@link TrajectoryEnvelope} in which the robot is parked.
	 * @param location A label representing the {@link TrajectoryEnvelope}.
	 */
	public void placeRobot(final int robotID, Pose currentPose, TrajectoryEnvelope parking, String location) {

		if (solver == null) {
			metaCSPLogger.severe("Solver not initialized, please call method setupSolver() first!");
			throw new Error("Solver not initialized, please call method setupSolver() first!");
		}

		synchronized (solver) {	
			//Create a new parking envelope
			long time = getCurrentTimeInMillis();

			//Can provide null parking or null currentPose, but not both
			if (parking == null) parking = solver.createParkingEnvelope(robotID, PARKING_DURATION, currentPose, location, getFootprint(robotID));
			else currentPose = parking.getTrajectory().getPose()[0];

			AllenIntervalConstraint release = new AllenIntervalConstraint(AllenIntervalConstraint.Type.Release, new Bounds(time, time));
			release.setFrom(parking);
			release.setTo(parking);
			if (!solver.addConstraint(release)) {
				metaCSPLogger.severe("Could not release " + parking + " with constriant " + release);
				throw new Error("Could not release " + parking + " with constriant " + release);
			}
			metaCSPLogger.info("Placed " + parking.getComponent() + " in pose " + currentPose + ": " + parking);

			TrackingCallback cb = new TrackingCallback() {
				@Override
				public void beforeTrackingStart() {
					if (trackingCallbacks.containsKey(robotID)) trackingCallbacks.get(robotID).beforeTrackingStart();
				}
				@Override
				public void onTrackingStart() {
					if (trackingCallbacks.containsKey(robotID)) trackingCallbacks.get(robotID).onTrackingStart();
				}
				@Override
				public void onNewGroundEnvelope() {
					if (trackingCallbacks.containsKey(robotID)) trackingCallbacks.get(robotID).onNewGroundEnvelope();
				}
				@Override
				public void beforeTrackingFinished() {
					if (trackingCallbacks.containsKey(robotID)) trackingCallbacks.get(robotID).beforeTrackingFinished();
				}
				@Override
				public void onTrackingFinished() {
					if (trackingCallbacks.containsKey(robotID)) trackingCallbacks.get(robotID).onTrackingFinished();
				}
				@Override
				public String[] onPositionUpdate() {
					if (trackingCallbacks.containsKey(robotID)) return trackingCallbacks.get(robotID).onPositionUpdate();
					return null;
				}
			};

			//Now start the tracker for this parking (will be ended by call to addMissions for this robot)
			final TrajectoryEnvelopeTrackerDummy tracker = new TrajectoryEnvelopeTrackerDummy(parking, 300, TEMPORAL_RESOLUTION, this, cb) {
				@Override
				public long getCurrentTimeInMillis() {
					return tec.getCurrentTimeInMillis();
				}
			};

			currentParkingEnvelopes.add(tracker.getTrajectoryEnvelope());				

			synchronized (trackers) {
				trackers.put(robotID, tracker);
			}

		}
	}

	public FleetVisualization getVisualization() {
		return this.viz;
	}

	public HashSet<Dependency> getCurrentDependencies() {
		return this.currentDependencies;
	}

	/**
	 * Get the path index beyond which a robot should not navigate, given the {@link TrajectoryEnvelope} of another robot.  
	 * @param te1 The {@link TrajectoryEnvelope} of the leading robot.
	 * @param te2 The {@link TrajectoryEnvelope} of the yielding robot.
	 * @param currentPIR1 The current path index of the leading robot.
	 * @param te1Start The path index
	 * @param te1End
	 * @param te2Start
	 * @return
	 */
	public int getCriticalPoint(int yieldingRobotID, CriticalSection cs, int leadingRobotCurrentPathIndex) {

		//Number of additional path points robot 2 should stay behind robot 1
		int TRAILING_PATH_POINTS = 3;

		int leadingRobotStart = -1;
		int yieldingRobotStart = -1;
		int leadingRobotEnd = -1;
		int yieldingRobotEnd = -1;
		if (cs.getTe1().getRobotID() == yieldingRobotID) {
			leadingRobotStart = cs.getTe2Start();
			yieldingRobotStart = cs.getTe1Start();
			leadingRobotEnd = cs.getTe2End();
			yieldingRobotEnd = cs.getTe1End();
		}
		else {
			leadingRobotStart = cs.getTe1Start();
			yieldingRobotStart = cs.getTe2Start();
			leadingRobotEnd = cs.getTe1End();
			yieldingRobotEnd = cs.getTe2End();
		}

		TrajectoryEnvelope leadingRobotTE = null;
		TrajectoryEnvelope yieldingRobotTE = null;
		if (cs.getTe1().getRobotID() == yieldingRobotID) {
			leadingRobotTE = cs.getTe2();
			yieldingRobotTE = cs.getTe1();
		}
		else {
			leadingRobotTE = cs.getTe1();
			yieldingRobotTE = cs.getTe2();			
		}

		if (leadingRobotCurrentPathIndex <= leadingRobotStart) {
			return Math.max(0, yieldingRobotStart-TRAILING_PATH_POINTS);
		}

		//Compute sweep of robot 1's footprint from current position to LOOKAHEAD
		Pose leadingRobotPose = leadingRobotTE.getTrajectory().getPose()[leadingRobotCurrentPathIndex];
		Geometry leadingRobotInPose = TrajectoryEnvelope.getFootprint(leadingRobotTE.getFootprint(), leadingRobotPose.getX(), leadingRobotPose.getY(), leadingRobotPose.getTheta());
		for (int i = leadingRobotCurrentPathIndex+1; i <= leadingRobotEnd; i++) {
			Pose leadingRobotNextPose = leadingRobotTE.getTrajectory().getPose()[i];
			leadingRobotInPose = leadingRobotInPose.union(TrajectoryEnvelope.getFootprint(leadingRobotTE.getFootprint(), leadingRobotNextPose.getX(), leadingRobotNextPose.getY(), leadingRobotNextPose.getTheta()));			
		}

		//Return pose at which yielding robot should stop given driving robot's projected sweep
		for (int i = yieldingRobotStart; i < yieldingRobotEnd; i++) {
			Pose yieldingRobotPose = yieldingRobotTE.getTrajectory().getPose()[i];
			Geometry yieldingRobotInPose = TrajectoryEnvelope.getFootprint(yieldingRobotTE.getFootprint(), yieldingRobotPose.getX(), yieldingRobotPose.getY(), yieldingRobotPose.getTheta());
			if (leadingRobotInPose.intersects(yieldingRobotInPose)) {
				return Math.max(0, i-TRAILING_PATH_POINTS);
			}
		}

		//The only situation where the above has not returned is when robot 2 should
		//stay "parked", therefore wait at index 0
		return Math.max(0, yieldingRobotStart-TRAILING_PATH_POINTS);

	}

	@Deprecated
	public int getCriticalPoint(TrajectoryEnvelope te1, TrajectoryEnvelope te2, int currentPIR1, int te1Start, int te1End, int te2Start) {

		//Number of additional path points robot 2 should stay behind robot 1
		int TRAILING_PATH_POINTS = 3;

		//How far ahead in robot 1's trajectory should we look ahead for collisions
		//(all the way to end of critical section if robots can drive in opposing directions)
		int LOOKAHEAD = te1End; //5;

		//How far into the critical section has robot 1 reached 
		int depthRobot1 = currentPIR1-te1Start;

		//If robot 1 not in critical section yet, return critical section start as waiting point for robot 2
		if (depthRobot1 < 0) return Math.max(0, te2Start-TRAILING_PATH_POINTS);	

		//Compute sweep of robot 1's footprint from current position to LOOKAHEAD
		Pose robot1Pose = te1.getTrajectory().getPose()[currentPIR1];
		Geometry robot1InPose = TrajectoryEnvelope.getFootprint(te1.getFootprint(), robot1Pose.getX(), robot1Pose.getY(), robot1Pose.getTheta());
		for (int i = 1; currentPIR1+i < LOOKAHEAD && currentPIR1+i < te1.getTrajectory().getPose().length; i++) {
			Pose robot1NextPose = te1.getTrajectory().getPose()[currentPIR1+i];
			robot1InPose = robot1InPose.union(TrajectoryEnvelope.getFootprint(te1.getFootprint(), robot1NextPose.getX(), robot1NextPose.getY(), robot1NextPose.getTheta()));			
		}

		//Starting from the same depth into the critical section as that of robot 1,
		//and decreasing the pose index backwards down to te2Start,
		//return the pose index as soon as robot 2's footprint does not intersect with robot 1's sweep
		for (int i = Math.min(te2.getTrajectory().getPose().length-1, te2Start+depthRobot1); i > te2Start-1; i--) {
			Pose robot2Pose = te2.getTrajectory().getPose()[i];
			Geometry robot2InPose = TrajectoryEnvelope.getFootprint(te2.getFootprint(), robot2Pose.getX(), robot2Pose.getY(), robot2Pose.getTheta());
			if (!robot1InPose.intersects(robot2InPose)) {
				return Math.max(0, i-TRAILING_PATH_POINTS);
			}
		}

		//The only situation where the above has not returned is when robot 2 should
		//stay "parked", therefore wait at index 0
		return Math.max(0, te2Start-TRAILING_PATH_POINTS);

		//		//The above should have returned, as i is decremented until robot 2 is before the critical section
		//		System.out.println("Robot" + te1.getRobotID() + ": start=" +te1Start + " depth=" + depthRobot1 + " Robot" + te2.getRobotID() + ": start=" + te2Start);
		//		metaCSPLogger.severe("Could not determine CP for " + te2);
		//		throw new Error("Could not determine CP for " + te2);
	}

	public boolean atStoppingPoint(int robotID) {
		return stoppingPointTimers.containsKey(robotID);
	}
	
	//Spawn a waiting thread at this stopping point
	protected void spawnWaitingThread(final int robotID, final int index, final int duration) {
		Thread stoppingPointTimer = new Thread() {
			private long startTime = Calendar.getInstance().getTimeInMillis();
			@Override
			public void run() {
				System.out.println("WAITING THREAD STARTS FOR " + robotID);
				while (Calendar.getInstance().getTimeInMillis()-startTime < duration) {
					try { Thread.sleep(100); }
					catch (InterruptedException e) { e.printStackTrace(); }
				}
				System.out.println("WAITING THREAD FINISHED FOR " + robotID);
				synchronized(stoppingPoints) {
					stoppingPoints.get(robotID).remove((int)index);
					stoppingTimes.get(robotID).remove((int)index);
					stoppingPointTimers.remove(robotID);
				}
				updateDependencies();
			}
		};
		stoppingPointTimers.put(robotID,stoppingPointTimer);
		stoppingPointTimer.start();
	}

	// Deadlock:
	//  R1-x/R2-y
	//  R2-z/R1-w 
	// (R1-x/R2-y)              --> deadlock iff (y>z AND w>x)
	// 
	//  R1-x1/R2-x2
	//  R2-x3/R3-x4
	//  R3-x5/R4-x6
	//  ...
	//  Rj-x(2j-1)/R(j+1)-x(2j)
	//  ...
	//  Rn-x(2n-1)/R1-x(2n)     
	// (R1-x1/R2-x2)            --> deadlock iff x(2j)>x(2j+1) forall j in [1..n] AND x(2n)>x1


	private void findCycles() {
		DirectedMultigraph<Integer,Dependency> g = new DirectedMultigraph<Integer, Dependency>(Dependency.class);
		for (Dependency dep : currentDependencies) {
			if (!g.containsVertex(dep.getWaitingRobotID())) g.addVertex(dep.getWaitingRobotID());
			if (!g.containsVertex(dep.getDrivingRobotID())) g.addVertex(dep.getDrivingRobotID());
			g.addEdge(dep.getWaitingRobotID(), dep.getDrivingRobotID(), dep);
		}
		JohnsonSimpleCycles<Integer, Dependency> cycleFinder = new JohnsonSimpleCycles<Integer, Dependency>(g);
		List<List<Integer>> cycles = cycleFinder.findSimpleCycles();
		for (List<Integer> cycle : cycles) {
			ArrayList<ArrayList<Dependency>> edgesAlongCycle = new ArrayList<ArrayList<Dependency>>();
			Collections.reverse(cycle);
			for (int i = 0; i < cycle.size(); i++) {
				if (i < cycle.size()-1) {
					edgesAlongCycle.add(new ArrayList<Dependency>(g.getAllEdges(cycle.get(i), cycle.get(i+1))));
				}
				else {
					edgesAlongCycle.add(new ArrayList<Dependency>(g.getAllEdges(cycle.get(i), cycle.get(0))));
				}
			}


			Dependency anUnsafeDep = null;
			for (int i = 0; i < edgesAlongCycle.size()-1; i++) {
				boolean safe = true;
				for (Dependency dep1 : edgesAlongCycle.get(i)) {
					for (Dependency dep2 : edgesAlongCycle.get(i+1)) {
						if (unsafePair(dep1, dep2)) {
							safe = false;
							anUnsafeDep = dep1;
						}
					}
				}
				if (safe) {
					System.out.println("Cycle: " + edgesAlongCycle + " is deadlock-free");
					break;
				}
				if (i == edgesAlongCycle.size()-2) {
					System.out.println("Cycle: " + edgesAlongCycle + " is NOT deadlock-free");
					spawnReplanning(edgesAlongCycle);
					synchronized (disallowedDependencies) {
						disallowedDependencies.add(anUnsafeDep);
					}
				}
			}

		}
	}
	
	private boolean atCP(int robotID) {
		return (Math.abs(getRobotReport(robotID).getCriticalPoint() - getRobotReport(robotID).getPathIndex()) <= 1);
	}
	
	private void spawnReplanning(ArrayList<ArrayList<Dependency>> deadlockedDeps) {
		for (ArrayList<Dependency> depList : deadlockedDeps) {
			for (Dependency dep : depList) {
				if (atCP(dep.getWaitingRobotID()) && atCP(dep.getDrivingRobotID())) {
					System.out.println("Should replan path of either Robot" + dep.getWaitingRobotID() + " or Robot" + dep.getDrivingRobotID());
				}
			}
		}
	}

	private boolean unsafePair(Dependency dep1, Dependency dep2) {
		if (dep2.getWaitingPoint() <= dep1.getReleasingPoint()) return true;
		return false;
	}

	//returns true if robot1 should go before robot2
	//returns false if robot2 should go before robot1
	private boolean getOrder(AbstractTrajectoryEnvelopeTracker robotTracker1, RobotReport robotReport1, AbstractTrajectoryEnvelopeTracker robotTracker2, RobotReport robotReport2, CriticalSection cs) {

		if (breakDeadlocks) {
			synchronized (disallowedDependencies) {
				for (Dependency dep : disallowedDependencies) {
					if (dep.getWaitingRobotID() == robotTracker1.getTrajectoryEnvelope().getRobotID() && dep.getDrivingRobotID() == robotTracker2.getTrajectoryEnvelope().getRobotID() && cs.getTe2End() == dep.getReleasingPoint()) {
						//System.out.println("DISALLOWED " + dep.getWaitingRobotID() + " waits for " + dep.getDrivingRobotID());
						return true;
					}
					else if (dep.getWaitingRobotID() == robotTracker2.getTrajectoryEnvelope().getRobotID() && dep.getDrivingRobotID() == robotTracker1.getTrajectoryEnvelope().getRobotID() && cs.getTe1End() == dep.getReleasingPoint()) {
						//System.out.println("DISALLOWED " + dep.getWaitingRobotID() + " waits for " + dep.getDrivingRobotID());
						return false;
					}
				}
			}
		}

		ForwardModel fm1 = getForwardModel(robotTracker1.getTrajectoryEnvelope().getRobotID());
		ForwardModel fm2 = getForwardModel(robotTracker2.getTrajectoryEnvelope().getRobotID());
		boolean canStopRobot1 = false;
		boolean canStopRobot2 = false;
		if (robotTracker1.getCriticalPoint() != -1 && robotTracker1.getCriticalPoint() <= cs.getTe1Start()) canStopRobot1 = true;
		else canStopRobot1 = fm1.canStop(robotTracker1.getTrajectoryEnvelope(), robotReport1, cs.getTe1Start());
		if (robotTracker2.getCriticalPoint() != -1 && robotTracker2.getCriticalPoint() <= cs.getTe2Start()) canStopRobot2 = true;
		else canStopRobot2 = fm2.canStop(robotTracker2.getTrajectoryEnvelope(), robotReport2, cs.getTe2Start());

		if (!canStopRobot1 && !canStopRobot2) {
			metaCSPLogger.severe("** WARNING ** Neither robot can stop at " + cs);
			//throw new Error("Neither robot can stop at " + cs);
			if (cs.getTe1Start()-robotReport1.getPathIndex() < cs.getTe2Start()-robotReport2.getPathIndex()) return true;
			return false;
		}

		//If both can stop, use ordering function (or closest if no ordering function)
		if (canStopRobot1 && canStopRobot2) {
			metaCSPLogger.finest("Both robots can stop at " + cs);
			
			if (yieldIfParking) {
				if (cs.getTe1End() == cs.getTe1().getPathLength()-1) {
					metaCSPLogger.info("Robot" + cs.getTe1().getRobotID() + " will park in " + cs + ", letting Robot" + cs.getTe2().getRobotID() + " go first");
					return false;
				}
				if (cs.getTe2End() == cs.getTe2().getPathLength()-1) {
					metaCSPLogger.info("Robot" + cs.getTe2().getRobotID() + " will park in " + cs + ", letting Robot" + cs.getTe1().getRobotID() + " go first");
					return true;
				}
			}
			
			RobotAtCriticalSection r1atcs = new RobotAtCriticalSection(robotTracker1, cs);
			RobotAtCriticalSection r2atcs = new RobotAtCriticalSection(robotTracker2, cs);
			boolean ret = false;
			if (this.comparators.size() > 0) ret = (this.comparators.compare(r1atcs,r2atcs) < 0);
			//No ordering function, decide an ordering based on distance (closest goes first)
			else ret = ((cs.getTe2Start()-robotReport2.getPathIndex()) > (cs.getTe1Start()-robotReport1.getPathIndex()));
			if (ret && muted.contains(robotTracker2.getTrajectoryEnvelope().getRobotID())) return false;
			if (!ret && muted.contains(robotTracker1.getTrajectoryEnvelope().getRobotID())) return true;
			return ret;
		}
		else if (!canStopRobot1) {
			metaCSPLogger.finest("Robot" + robotTracker1.getTrajectoryEnvelope().getRobotID() + " cannot stop at " + cs);
			return true;
		}
		else {
			metaCSPLogger.finest("Robot" + robotTracker2.getTrajectoryEnvelope().getRobotID() + " cannot stop at " + cs);
			return false;
		}
	}

	private boolean isAhead(CriticalSection cs, RobotReport rr1, RobotReport rr2) {
		//Robot 1 is ahead --> return true
		PoseSteering[] pathRobot1 = cs.getTe1().getTrajectory().getPoseSteering();
		PoseSteering[] pathRobot2 = cs.getTe2().getTrajectory().getPoseSteering();
		double dist1 = 0.0;
		double dist2 = 0.0;
		for (int i = cs.getTe1Start(); i < rr1.getPathIndex()-1; i++) {
			dist1 += pathRobot1[i].getPose().getPosition().distance(pathRobot1[i+1].getPose().getPosition());
		}
		for (int i = cs.getTe2Start(); i < rr2.getPathIndex()-1; i++) {
			dist2 += pathRobot2[i].getPose().getPosition().distance(pathRobot2[i+1].getPose().getPosition());
		}
		if (dist1 > dist2) return true;
		return false;
	}

	//Update and set the critical points
	public void updateDependencies() {

		//System.out.println("Caller of updateDependencies(): " + Thread.currentThread().getStackTrace()[2]);

		HashMap<Integer,TreeSet<Dependency>> currentDeps = new HashMap<Integer,TreeSet<Dependency>>();

		//Make deps from un-reached stopping points
		Set<Integer> robotIDs = trackers.keySet();
		for (int robotID : robotIDs) {
			AbstractTrajectoryEnvelopeTracker robotTracker = trackers.get(robotID);
			RobotReport robotReport = robotTracker.getRobotReport();
			synchronized(stoppingPoints) {
				if (stoppingPoints.containsKey(robotID)) {
					for (int i = 0; i < stoppingPoints.get(robotID).size(); i++) {
						int stoppingPoint = stoppingPoints.get(robotID).get(i);
						int duration = stoppingTimes.get(robotID).get(i);
						if (robotReport.getPathIndex() <= stoppingPoint) {
							Dependency dep = new Dependency(robotTracker.getTrajectoryEnvelope(), null, stoppingPoint, 0, robotTracker, null);
							if (!currentDeps.containsKey(robotID)) currentDeps.put(robotID, new TreeSet<Dependency>());
							currentDeps.get(robotID).add(dep);					
						}
						//Start waiting thread if the stopping point has been reached
						//if (Math.abs(robotReport.getPathIndex()-stoppingPoint) <= 1 && robotReport.getCriticalPoint() == stoppingPoint && !stoppingPointTimers.containsKey(robotID)) {
						if (Math.abs(robotReport.getPathIndex()-stoppingPoint) <= 1 && robotReport.getCriticalPoint() <= stoppingPoint && !stoppingPointTimers.containsKey(robotID)) {
							spawnWaitingThread(robotID, i, duration);
						}
//						else {
//							System.out.println("NOT YET for Robot" + robotID + " stoppingPoint = " + stoppingPoint + " rr.cp = " + robotReport.getCriticalPoint() + " rr.pi = " + robotReport.getPathIndex());
//						}
					}
				}
			}
		}

		//Make deps from critical sections, and remove obsolete critical sections
		synchronized(allCriticalSections) {

			ArrayList<CriticalSection> toRemove = new ArrayList<CriticalSection>();
			for (CriticalSection cs : allCriticalSections) {

				//Will be assigned depending on current situation of robot reports...
				int waitingRobotID = -1;
				int drivingRobotID = -1;
				AbstractTrajectoryEnvelopeTracker waitingTracker = null;
				AbstractTrajectoryEnvelopeTracker drivingTracker = null;
				int drivingCurrentIndex = -1;
				int waitingCurrentIndex = -1;
				TrajectoryEnvelope waitingTE = null;
				TrajectoryEnvelope drivingTE = null;

				AbstractTrajectoryEnvelopeTracker robotTracker1 = trackers.get(cs.getTe1().getRobotID());
				RobotReport robotReport1 = robotTracker1.getRobotReport();
				AbstractTrajectoryEnvelopeTracker robotTracker2 = trackers.get(cs.getTe2().getRobotID());
				RobotReport robotReport2 = robotTracker2.getRobotReport();

				//One or both robots past end of the critical section --> critical section is obsolete
				if (robotReport1.getPathIndex() > cs.getTe1End() || robotReport2.getPathIndex() > cs.getTe2End()) {
					toRemove.add(cs);
					metaCSPLogger.finest("Obsolete critical section\n\t" + cs);
					continue;
				}


				//If one robot is parked, make the other wait
				if (robotTracker1 instanceof TrajectoryEnvelopeTrackerDummy || robotTracker2 instanceof TrajectoryEnvelopeTrackerDummy) {

					if (robotTracker1 instanceof TrajectoryEnvelopeTrackerDummy) {
						waitingTE = cs.getTe2();
						drivingTE = cs.getTe1();
						waitingRobotID = waitingTE.getRobotID();
						drivingRobotID = drivingTE.getRobotID();
						waitingTracker = trackers.get(waitingRobotID);
						drivingTracker = trackers.get(drivingRobotID);
						drivingCurrentIndex = robotReport1.getPathIndex();
						waitingCurrentIndex = robotReport2.getPathIndex(); 
					}
					else if (robotTracker2 instanceof TrajectoryEnvelopeTrackerDummy) {
						waitingTE = cs.getTe1();
						drivingTE = cs.getTe2();
						waitingRobotID = waitingTE.getRobotID();
						drivingRobotID = drivingTE.getRobotID();
						waitingTracker = trackers.get(waitingRobotID);
						drivingTracker = trackers.get(drivingRobotID);
						drivingCurrentIndex = robotReport2.getPathIndex();
						waitingCurrentIndex = robotReport1.getPathIndex(); 
					}

					int waitingPoint = getCriticalPoint(waitingRobotID, cs, drivingCurrentIndex);
					//System.out.println("Robot" + drivingRobotID + " is parked, so Robot" + waitingRobotID + " SHOULD wait");
					boolean alreadyCommunicated = (communicatedCPs.containsKey(waitingTracker) && communicatedCPs.get(waitingTracker) == waitingPoint); 
					if (alreadyCommunicated || waitingPoint >= waitingCurrentIndex) {
						metaCSPLogger.finest("Robot" + drivingRobotID + " is parked, so Robot" + waitingRobotID + " will have to wait");	
						//Make new dependency
						int drivingCSEnd = -1;
//						//with +1
//						if (waitingRobotID == cs.getTe1().getRobotID()) drivingCSEnd = Math.min(cs.getTe2End()+1,cs.getTe2().getTrajectory().getPose().length-1);
//						else drivingCSEnd = Math.min(cs.getTe1End()+1,cs.getTe1().getTrajectory().getPose().length-1);
						if (waitingRobotID == cs.getTe1().getRobotID()) drivingCSEnd = cs.getTe2End();
						else drivingCSEnd = cs.getTe1End();
						Dependency dep = new Dependency(waitingTE, drivingTE, waitingPoint, drivingCSEnd, waitingTracker, drivingTracker);
						if (!currentDeps.containsKey(waitingRobotID)) currentDeps.put(waitingRobotID, new TreeSet<Dependency>());
						currentDeps.get(waitingRobotID).add(dep);
						criticalSectionsToDeps.put(cs, dep);
					}
					else {
						metaCSPLogger.finest("** Warning ** Robot" + waitingRobotID + " cannot stop at " + waitingPoint + " for Robot" + drivingRobotID + " (which is parked) because it is already at " + waitingCurrentIndex);
					}
				}

				//Both robots are driving, let's determine an ordering for them thru this critical section
				else {

					//Neither robot has reached the critical section --> follow ordering heuristic if FW model allows it
					if (robotReport1.getPathIndex() < cs.getTe1Start() && robotReport2.getPathIndex() < cs.getTe2Start()) {

						//If robot 1 has priority over robot 2
						if (getOrder(robotTracker1, robotReport1, robotTracker2, robotReport2, cs)) {
							drivingCurrentIndex = robotReport1.getPathIndex();
							waitingCurrentIndex = robotReport2.getPathIndex();
							waitingTE = cs.getTe2();
							drivingTE = cs.getTe1();
							metaCSPLogger.finest("Both Out (1) and Robot" + drivingTE.getRobotID() + " ahead of Robot" + waitingTE.getRobotID());
						}

						//If robot 2 has priority over robot 1
						else {
							drivingCurrentIndex = robotReport2.getPathIndex();
							waitingCurrentIndex = robotReport1.getPathIndex();
							waitingTE = cs.getTe1();
							drivingTE = cs.getTe2();
							metaCSPLogger.finest("Both Out (2) and Robot" + drivingTE.getRobotID() + " ahead of Robot" + waitingTE.getRobotID());
						}

					}

					//Robot 1 has not reached critical section, robot 2 in critical section --> robot 1 waits
					else if (robotReport1.getPathIndex() < cs.getTe1Start() && robotReport2.getPathIndex() >= cs.getTe2Start()) {
						drivingCurrentIndex = robotReport2.getPathIndex();
						waitingCurrentIndex = robotReport1.getPathIndex();
						waitingTE = cs.getTe1();
						drivingTE = cs.getTe2();
						metaCSPLogger.finest("One-in-one-out (1) and Robot" + drivingTE.getRobotID() + " ahead of Robot" + waitingTE.getRobotID());
					}

					//Robot 2 has not reached critical section, robot 1 in critical section --> robot 2 waits
					else if (robotReport1.getPathIndex() >= cs.getTe1Start() && robotReport2.getPathIndex() < cs.getTe2Start()) {
						drivingCurrentIndex = robotReport1.getPathIndex();
						waitingCurrentIndex = robotReport2.getPathIndex();
						waitingTE = cs.getTe2();
						drivingTE = cs.getTe1();
						metaCSPLogger.finest("One-in-one-out (2) and Robot" + drivingTE.getRobotID() + " ahead of Robot" + waitingTE.getRobotID());
					}

					//Both robots in critical section --> re-impose previously decided dependency
					else {
						//Robot 1 is ahead --> make robot 2 follow
						//if (robotReport1.getPathIndex()-cs.getTe1Start() > robotReport2.getPathIndex()-cs.getTe2Start()) {
						if (isAhead(cs, robotReport1, robotReport2)) {
							drivingCurrentIndex = robotReport1.getPathIndex();
							waitingCurrentIndex = robotReport2.getPathIndex();
							waitingTE = cs.getTe2();
							drivingTE = cs.getTe1();
						}
						//Robot 2 is ahead --> make robot 1 follow
						else {
							drivingCurrentIndex = robotReport2.getPathIndex();
							waitingCurrentIndex = robotReport1.getPathIndex();
							waitingTE = cs.getTe1();
							drivingTE = cs.getTe2();
						}
						metaCSPLogger.finest("In CS at start and Robot" + drivingTE.getRobotID() + " ahead of Robot" + waitingTE.getRobotID());
					}

					waitingRobotID = waitingTE.getRobotID();
					drivingRobotID = drivingTE.getRobotID();
					waitingTracker = trackers.get(waitingRobotID);
					drivingTracker = trackers.get(drivingRobotID);

					//Compute waiting path index point for waiting robot
					//int waitingPoint = getCriticalPoint(drivingTE, waitingTE, drivingCurrentIndex, drivingCSStart, drivingCSEnd, waitingCSStart);
					int waitingPoint = getCriticalPoint(waitingRobotID, cs, drivingCurrentIndex);
					if (waitingPoint >= 0) {		
						//Make new dependency
						int drivingCSEnd = -1;
						if (waitingRobotID == cs.getTe1().getRobotID()) drivingCSEnd = cs.getTe2End();
						else drivingCSEnd = cs.getTe1End();
						Dependency dep = new Dependency(waitingTE, drivingTE, waitingPoint, drivingCSEnd, waitingTracker, drivingTracker);
						if (!currentDeps.containsKey(waitingRobotID)) currentDeps.put(waitingRobotID, new TreeSet<Dependency>());
						currentDeps.get(waitingRobotID).add(dep);
						criticalSectionsToDeps.put(cs, dep);
					}
					else {
						//If robot is asked in an invalid path point, throw error and give up!
						metaCSPLogger.severe("Waiting point < 0 for critical section " + cs);
						throw new Error("Waiting point < 0 for critical section " + cs);
					}
				}
			}

			//Remove obsolete critical sections
			for (CriticalSection cs : toRemove) {
				this.criticalSectionsToDeps.remove(cs);
				this.allCriticalSections.remove(cs);
			}
		}

		synchronized(currentDependencies) {
			currentDependencies.clear();
			for (Integer robotID : robotIDs) {
				if (!currentDeps.containsKey(robotID)) {
					setCriticalPoint(robotID, -1);
				}
				else {
					Dependency firstDep = currentDeps.get(robotID).first();
					setCriticalPoint(firstDep.getWaitingTracker().getTrajectoryEnvelope().getRobotID(), firstDep.getWaitingPoint());
					currentDependencies.add(firstDep);
				}
			}
			findCycles();
		}		
	}


	/**
	 * Add a criterion for determining the order of robots through critical sections
	 * (comparator of {@link AbstractTrajectoryEnvelopeTracker}s). 
	 * Comparators are considered in the order in which they are added.
	 * @param c A new comparator for determining robot ordering through critical sections.
	 */
	public void addComparator(Comparator<RobotAtCriticalSection> c) {
		this.comparators.addComparator(c);
	}

	/**
	 * Update the set of current critical sections. This should be called every time
	 * a new set of {@link Mission}s has been successfully added.
	 */
	public void computeCriticalSections() {

		synchronized(allCriticalSections) {

			//Collect all driving envelopes
			ArrayList<TrajectoryEnvelope> drivingEnvelopes = new ArrayList<TrajectoryEnvelope>();
			for (AbstractTrajectoryEnvelopeTracker atet : trackers.values()) {
				if (!(atet instanceof TrajectoryEnvelopeTrackerDummy)) {
					drivingEnvelopes.add(atet.getTrajectoryEnvelope());
				}
			}

			//Compute critical sections between driving and new envelopes
			for (int i = 0; i < drivingEnvelopes.size(); i++) {
				for (int j = 0; j < envelopesToTrack.size(); j++) {	
					for (CriticalSection cs : getCriticalSections(drivingEnvelopes.get(i), envelopesToTrack.get(j))) {
						this.allCriticalSections.add(cs);
					}
				}
			}	

			//Compute critical sections between new envelopes
			for (int i = 0; i < envelopesToTrack.size(); i++) {
				for (int j = i+1; j < envelopesToTrack.size(); j++) {
					for (CriticalSection cs : getCriticalSections(envelopesToTrack.get(i), envelopesToTrack.get(j))) {
						this.allCriticalSections.add(cs);
					}
				}
			}

			//Compute critical sections between driving envelopes and current parking envelopes	
			for (int i = 0; i < drivingEnvelopes.size(); i++) {
				for (int j = 0; j < currentParkingEnvelopes.size(); j++) {
					if (drivingEnvelopes.get(i).getRobotID() != currentParkingEnvelopes.get(j).getRobotID()) {
						for (CriticalSection cs : getCriticalSections(drivingEnvelopes.get(i), currentParkingEnvelopes.get(j))) {
							this.allCriticalSections.add(cs);
						}
					}
				}
			}

			//Compute critical sections between new driving envelopes and current parking envelopes	
			for (int i = 0; i < envelopesToTrack.size(); i++) {
				for (int j = 0; j < currentParkingEnvelopes.size(); j++) {
					if (envelopesToTrack.get(i).getRobotID() != currentParkingEnvelopes.get(j).getRobotID()) {
						for (CriticalSection cs : getCriticalSections(envelopesToTrack.get(i), currentParkingEnvelopes.get(j))) {
							this.allCriticalSections.add(cs);
						}
					}
				}
			}
			
			filterCriticalSections();
			
			metaCSPLogger.info("There are now " + allCriticalSections.size() + " critical sections");
		}
	}
	
	private void filterCriticalSections() {
		ArrayList<CriticalSection> toAdd = new ArrayList<CriticalSection>();
		ArrayList<CriticalSection> toRemove = new ArrayList<CriticalSection>();
		int passNum = 0;
		do {
			passNum++;
			toAdd.clear();
			toRemove.clear();
			for (CriticalSection cs1 : allCriticalSections) {
				for (CriticalSection cs2 : allCriticalSections) {
					//If CS1 and CS2 are about the same pair of robots
					if (!cs1.equals(cs2) && cs1.getTe1().equals(cs2.getTe1()) && cs1.getTe2().equals(cs2.getTe2())) {
						int start11 = cs1.getTe1Start();
						int start12 = cs1.getTe2Start();
						int start21 = cs2.getTe1Start();
						int start22 = cs2.getTe2Start();
						int end11 = cs1.getTe1End();
						int end12 = cs1.getTe2End();
						int end21 = cs2.getTe1End();
						int end22 = cs2.getTe2End();
						Pose[] path1 = cs1.getTe1().getTrajectory().getPose();
						//CS1 before CS2
						if (start11 < start21) {
							//CS1 ends after CS2 starts
							if (end11 >= start21) {
								toRemove.add(cs1);
								toRemove.add(cs2);
								int newStart1 = start11;
								int newEnd1 = end21;
								int newStart2 = -1;
								int newEnd2 = -1;
								if (start12 < start22) {
									newStart2 = start12;
									newEnd2 = end22;
								}
								else {
									newStart2 = start22;
									newEnd2 = end12;
								}
								CriticalSection newCS = new CriticalSection(cs1.getTe1(), cs1.getTe2(), newStart1, newStart2, newEnd1, newEnd2); 
								toAdd.add(newCS);
								System.out.println("(Pass " + passNum + ") MERGED (ends-after-start): " + cs1 + " + " + cs2 + " = " + newCS);
							}
							//distance CS1.end -> CS2.start too small
							else if (path1[end11].distanceTo(path1[start21]) <= 1.1*getMaxFootprintDimension(cs1.getTe1().getRobotID())) {
								toRemove.add(cs1);
								toRemove.add(cs2);
								int newStart1 = start11;
								int newEnd1 = end21;
								int newStart2 = -1;
								int newEnd2 = -1;
								if (start12 < start22) {
									newStart2 = start12;
									newEnd2 = end22;
								}
								else {
									newStart2 = start22;
									newEnd2 = end12;
								}
								CriticalSection newCS = new CriticalSection(cs1.getTe1(), cs1.getTe2(), newStart1, newStart2, newEnd1, newEnd2); 
								toAdd.add(newCS);
								System.out.println("(Pass " + passNum + ") MERGED (too-close): " + cs1 + " + " + cs2 + " = " + newCS);								
							}
						}
					}
				}	
			}
			for (CriticalSection cs : toRemove) allCriticalSections.remove(cs);
			for (CriticalSection cs : toAdd) allCriticalSections.add(cs);
		}
		while (!toAdd.isEmpty() || !toRemove.isEmpty());
		
		toRemove.clear();
		for (int i = 0; i < allCriticalSections.size(); i++) {
			for (int j = i+1; j < allCriticalSections.size(); j++) {
				CriticalSection cs1 = allCriticalSections.get(i);
				CriticalSection cs2 = allCriticalSections.get(j);
				int start11 = cs1.getTe1Start();
				int start12 = cs1.getTe2Start();
				int start21 = cs2.getTe1Start();
				int start22 = cs2.getTe2Start();
				int end11 = cs1.getTe1End();
				int end12 = cs1.getTe2End();
				int end21 = cs2.getTe1End();
				int end22 = cs2.getTe2End();
				//If CS1 and CS2 are about the same pair of robots
				if (!cs1.equals(cs2) && cs1.getTe1().equals(cs2.getTe1()) && cs1.getTe2().equals(cs2.getTe2())) {
					//CS1 and CS2 are identical
					if (start11 == start21 && end11 == end21 && start12 == start22 && end12 == end22) {
						toRemove.add(cs1);
						System.out.println("(Pass " + passNum + ") REMOVED ONE OF " + cs1 + " and " + cs2);
					}
				}
			}
		}
		for (CriticalSection cs : toRemove) allCriticalSections.remove(cs);

	}

	protected CriticalSection[] getCriticalSections(TrajectoryEnvelope te1, TrajectoryEnvelope te2) {

		GeometricShapeVariable poly1 = te1.getEnvelopeVariable();
		GeometricShapeVariable poly2 = te2.getEnvelopeVariable();
		Geometry shape1 = ((GeometricShapeDomain)poly1.getDomain()).getGeometry();
		Geometry shape2 = ((GeometricShapeDomain)poly2.getDomain()).getGeometry();
		ArrayList<CriticalSection> css = new ArrayList<CriticalSection>();
		if (shape1.intersects(shape2)) {
			PoseSteering[] path1 = te1.getTrajectory().getPoseSteering();
			PoseSteering[] path2 = te2.getTrajectory().getPoseSteering();

			if (checkEscapePoses) {
				//Check that there is an "escape pose" along the paths 
				boolean safe = false;
				for (int j = 0; j < path1.length; j++) {
					Geometry placement1 = te1.makeFootprint(path1[j]);
					if (!placement1.intersects(shape2)) {
						safe = true;
						break;
					}
				}
				if (path1.length == 1 || path2.length == 1) safe = true;
				if (!safe) {
					metaCSPLogger.severe("** WARNING ** Cannot coordinate as one envelope is completely overlapped by the other!");
					metaCSPLogger.severe("** " + te1 + " <--> " + te2);
					//throw new Error("Cannot coordinate as one envelope is completely overlapped by the other!");
				}

				safe = false;
				for (int j = 0; j < path2.length; j++) {
					Geometry placement2 = te2.makeFootprint(path2[j]);
					if (!placement2.intersects(shape1)) {
						safe = true;
						break;
					}
				}
				if (path1.length == 1 || path2.length == 1) safe = true;
				if (!safe) {
					metaCSPLogger.severe("** WARNING ** Cannot coordinate as one envelope is completely overlapped by the other!");
					metaCSPLogger.severe("** " + te1 + " <--> " + te2);
					//throw new Error("Cannot coordinate as one envelope is completely overlapped by the other!");
				}
			}

			Geometry gc = shape1.intersection(shape2);
			ArrayList<Geometry> allIntersections = new ArrayList<Geometry>();
			if (gc.getNumGeometries() == 1) {
				allIntersections.add(gc);
			}
			else {
				for (int i = 1; i < gc.getNumGeometries(); i++) {
					Geometry prev = gc.getGeometryN(i-1);
					Geometry next = gc.getGeometryN(i);					
					if (prev.distance(next) < getMaxFootprintDimension(te1.getRobotID()) || prev.distance(next) < getMaxFootprintDimension(te2.getRobotID())) {
						allIntersections.add(prev.union(next).convexHull());
					}
					else {
						allIntersections.add(prev);
						if (i == gc.getNumGeometries()-1) allIntersections.add(next);
					}
				}
			}

			for (int i = 0; i < allIntersections.size(); i++) {
				ArrayList<Integer> te1Starts = new ArrayList<Integer>();
				ArrayList<Integer> te1Ends = new ArrayList<Integer>();
				ArrayList<Integer> te2Starts = new ArrayList<Integer>();
				ArrayList<Integer> te2Ends = new ArrayList<Integer>();
				Geometry g = allIntersections.get(i);
				boolean started = false;
				for (int j = 0; j < path1.length; j++) {
					Geometry placement1 = te1.makeFootprint(path1[j]);
					if (!started && placement1.intersects(g)) {
						started = true;
						te1Starts.add(j);
					}
					else if (started && !placement1.intersects(g)) {
						te1Ends.add(j);
						started = false;
					}
					if (started && j == path1.length-1) {
						te1Ends.add(path1.length-1);
					}
				}
				started = false;
				for (int j = 0; j < path2.length; j++) {
					Geometry placement2 = te2.makeFootprint(path2[j]);
					if (!started && placement2.intersects(g)) {
						started = true;
						te2Starts.add(j);
					}
					else if (started && !placement2.intersects(g)) {
						te2Ends.add(j);
						started = false;
					}
					if (started && j == path2.length-1) {
						te2Ends.add(path2.length-1);
					}
				}
				for (int k1 = 0; k1 < te1Starts.size(); k1++) {
					for (int k2 = 0; k2 < te2Starts.size(); k2++) {
						CriticalSection oneCS = new CriticalSection(te1, te2, te1Starts.get(k1), te2Starts.get(k2), te1Ends.get(k1), te2Ends.get(k2));
						css.add(oneCS);
					}					
				}
			}
		}
		
		return css.toArray(new CriticalSection[css.size()]);
	}

	protected void cleanUp(TrajectoryEnvelope te) {
		synchronized(solver) {
			metaCSPLogger.info("Cleaning up " + te);
			Constraint[] consToRemove = solver.getConstraintNetwork().getIncidentEdgesIncludingDependentVariables(te);
			solver.removeConstraints(consToRemove);
			solver.removeVariable(te);
		}
	}

	/**
	 * Get the {@link TrajectoryEnvelope} currently being tracked for a given robot. 
	 * @param robotID The ID of the robot for which to retrieve the {@link TrajectoryEnvelope} currently
	 * being tracked.
	 * @return The {@link TrajectoryEnvelope} currently being tracked for the given robot.
	 */
	public TrajectoryEnvelope getCurrentSuperEnvelope(int robotID) {
		return trackers.get(robotID).getTrajectoryEnvelope();
	}

	public void computeCriticalSectionsAndStartTrackingAddedMission() {
		synchronized(solver) {
			computeCriticalSections();
			updateDependencies();
			startTrackingAddedMissions();
		}
	}
	
	/**
	 * Add a {@link TrackingCallback} that will be called by the tracker of a given robot.
	 * @param robotID The ID of the robot to which the callback should be attached.
	 * @param cb A callback object.
	 */
	public void addTrackingCallback(int robotID, TrackingCallback cb) {
		trackingCallbacks.put(robotID, cb);
	}

	/**
	 * Start the trackers associated to the last batch of {@link Mission}s that has been added.
	 */
	public void startTrackingAddedMissions() {

		synchronized(solver) {
			for (final TrajectoryEnvelope te : envelopesToTrack) {
				TrajectoryEnvelopeTrackerDummy startParkingTracker = (TrajectoryEnvelopeTrackerDummy)trackers.get(te.getRobotID());
				final TrajectoryEnvelope startParking = startParkingTracker.getTrajectoryEnvelope();
				//Create end parking envelope
				final TrajectoryEnvelope endParking = solver.createParkingEnvelope(te.getRobotID(), PARKING_DURATION, te.getTrajectory().getPose()[te.getTrajectory().getPose().length-1], "whatever", getFootprint(te.getRobotID()));

				//Driving meets final parking
				AllenIntervalConstraint meets1 = new AllenIntervalConstraint(AllenIntervalConstraint.Type.Meets);
				meets1.setFrom(te);
				meets1.setTo(endParking);

				if (!solver.addConstraints(meets1)) {
					metaCSPLogger.severe("ERROR: Could not add constraints " + meets1);
					throw new Error("Could not add constraints " + meets1);		
				}

				//Add onStart call back that cleans up parking tracker
				//Note: onStart is triggered only when earliest start of this tracker's envelope is < current time
				TrackingCallback cb = new TrackingCallback() {

					@Override
					public void beforeTrackingStart() {
						
						if (trackingCallbacks.containsKey(te.getRobotID())) trackingCallbacks.get(te.getRobotID()).beforeTrackingStart();
						
						//Sleep for one control period
						//(allows to impose critical points before tracking actually starts)
						try { Thread.sleep(CONTROL_PERIOD); }
						catch (InterruptedException e) { e.printStackTrace(); }
					}

					@Override
					public void onTrackingStart() {
						if (trackingCallbacks.containsKey(te.getRobotID())) trackingCallbacks.get(te.getRobotID()).onTrackingStart();
						if (viz != null) viz.addEnvelope(te);
					}

					@Override
					public void onNewGroundEnvelope() {
						if (trackingCallbacks.containsKey(te.getRobotID())) trackingCallbacks.get(te.getRobotID()).onNewGroundEnvelope();
					}

					@Override
					public void beforeTrackingFinished() {
						if (trackingCallbacks.containsKey(te.getRobotID())) trackingCallbacks.get(te.getRobotID()).beforeTrackingFinished();
					}

					@Override
					public void onTrackingFinished() {

						if (trackingCallbacks.containsKey(te.getRobotID())) trackingCallbacks.get(te.getRobotID()).onTrackingFinished();
						
						synchronized (solver) {
							metaCSPLogger.info("Tracking finished for " + te);

							if (viz != null) viz.removeEnvelope(te);

							//reset stopping points
							synchronized(stoppingPoints) {
								stoppingPoints.remove(te.getRobotID());
								stoppingTimes.remove(te.getRobotID());
							}

							//remove critical sections in which this robot is involved
							synchronized(allCriticalSections) {
								ArrayList<CriticalSection> toRemove = new ArrayList<CriticalSection>();
								for (CriticalSection cs : allCriticalSections) {
									if (cs.getTe1().getRobotID() == te.getRobotID() || cs.getTe2().getRobotID() == te.getRobotID()) toRemove.add(cs);
								}
								for (CriticalSection cs : toRemove) allCriticalSections.remove(cs);

							}

							//clean up the old parking envelope
							cleanUp(startParking);
							currentParkingEnvelopes.remove(startParking);

							//clean up this tracker's TE
							cleanUp(te);

							//remove communicatedCP entry
							communicatedCPs.remove(trackers.get(te.getRobotID()));

							//make a new parking tracker (park the robot)
							placeRobot(te.getRobotID(), null, endParking, null);

							synchronized (disallowedDependencies) {
								ArrayList<Dependency> toRemove = new ArrayList<Dependency>();
								for (Dependency dep : disallowedDependencies) {
									if (dep.getDrivingRobotID() == te.getRobotID() || dep.getWaitingRobotID() == te.getRobotID()) {
										toRemove.add(dep);
									}
								}
								for (Dependency dep : toRemove) disallowedDependencies.remove(dep);
							}

							computeCriticalSections();
							updateDependencies();							
						}

					}

					@Override
					public String[] onPositionUpdate() {
						if (trackingCallbacks.containsKey(te.getRobotID())) return trackingCallbacks.get(te.getRobotID()).onPositionUpdate();
						return null;
					}

				};

				//Make a new tracker for the driving trajectory envelope
				final AbstractTrajectoryEnvelopeTracker tracker = getNewTracker(te, cb);

				synchronized (trackers) {
					trackers.put(te.getRobotID(), tracker);
				}

				//Now we can signal the parking that it can end (i.e., its deadline will no longer be prolonged)
				//Note: the parking tracker will anyway wait to exit until earliest end time has been reached
				startParkingTracker.finishParking();			
			}
			envelopesToTrack.clear();
		}
	}

	/**
	 * Add one or more missions for one or more robots. If more than one mission is specified for
	 * a robot <code>r</code>, then all the robot's missions are concatenated.
	 * NOTE: For each robot <code>r</code>, all missions should be either defined with a path file or with
	 * an array of {@link PoseSteering}s (pathfile- and path-specified missions cannot be mixed for one robot).
	 *   
	 * @param missions One or more {@link Mission}s, for one or more robots.
	 * @return <code>true</code> iff for all {@link Mission}s the relevant robot is not already
	 * engaged in another mission.
	 */
	public boolean addMissions(Mission ... missions) {

		if (solver == null) {
			metaCSPLogger.severe("Solvers not initialized, please call method setupSolver()");
			throw new Error("Solvers not initialized, please call method setupSolver()");
		}

		HashMap<Integer,ArrayList<Mission>> robotsToMissions = new HashMap<Integer,ArrayList<Mission>>();
		for (Mission m : missions) {
			if (robotsToMissions.get(m.getRobotID()) == null) robotsToMissions.put(m.getRobotID(), new ArrayList<Mission>());
			robotsToMissions.get(m.getRobotID()).add(m);
		}

		for (Entry<Integer,ArrayList<Mission>> e : robotsToMissions.entrySet()) {
			int robotID = e.getKey();
			if (!isFree(robotID)) return false;
		}

		for (Entry<Integer,ArrayList<Mission>> e : robotsToMissions.entrySet()) {
			int robotID = e.getKey();
			synchronized (solver) {
				//Get start parking tracker and envelope
				TrajectoryEnvelopeTrackerDummy startParkingTracker = (TrajectoryEnvelopeTrackerDummy)trackers.get(robotID);
				TrajectoryEnvelope startParking = startParkingTracker.getTrajectoryEnvelope();
				ArrayList<Constraint> consToAdd = new ArrayList<Constraint>();
				//				String finalDestLocation = "";
				ArrayList<PoseSteering> overallPath = new ArrayList<PoseSteering>();
				for (Mission m : e.getValue()) {						
					for (PoseSteering ps : m.getPath()) {
						overallPath.add(ps);
					}
				}

				//Create a big overall driving envelope
				TrajectoryEnvelope te = null;
				te = solver.createEnvelopeNoParking(robotID, overallPath.toArray(new PoseSteering[overallPath.size()]), "Driving", getFootprint(robotID));

				//Add mission stopping points
				synchronized(stoppingPoints) {
					for (int i = 0; i < e.getValue().size(); i++) {
						Mission m = e.getValue().get(i);
						for (Entry<Pose,Integer> entry : m.getStoppingPoints().entrySet()) {
							Pose stoppingPose = entry.getKey();
							int stoppingPoint = te.getSequenceNumber(new Coordinate(stoppingPose.getX(), stoppingPose.getY()));
							if (stoppingPoint == te.getPathLength()-1) stoppingPoint -= 2;
							int duration = entry.getValue();
							if (!stoppingPoints.keySet().contains(robotID)) {
								stoppingPoints.put(robotID, new ArrayList<Integer>());
								stoppingTimes.put(robotID, new ArrayList<Integer>());
							}
							if (!stoppingPoints.get(robotID).contains(stoppingPoint)) {
								stoppingPoints.get(robotID).add(stoppingPoint);
								stoppingTimes.get(robotID).add(duration);
							}
						}
					}
				
					//If many missions, add destinations as stopping points
					for (int i = 0; i < e.getValue().size()-1; i++) {
						Mission m = e.getValue().get(i);
						Pose destPose = m.getToPose();
						int stoppingPoint = te.getSequenceNumber(new Coordinate(destPose.getX(), destPose.getY()));
						if (!stoppingPoints.keySet().contains(robotID)) {
							stoppingPoints.put(robotID, new ArrayList<Integer>());
							stoppingTimes.put(robotID, new ArrayList<Integer>());
						}
						if (!stoppingPoints.get(robotID).contains(stoppingPoint)) {
							stoppingPoints.get(robotID).add(stoppingPoint);
							stoppingTimes.get(robotID).add(DEFAULT_STOPPING_TIME);
						}
					}
					metaCSPLogger.info("Stopping points along trajectory for Robot" + robotID + ": " + stoppingPoints.get(robotID));
				}

				//Put in more realistic DTs computed with the RK4 integrator
				//				System.out.println(">> Computing DTs...");
				//				double dts[] = TrajectoryEnvelopeTrackerRK4.computeDTs(te.getTrajectory(), this.getMaxVelocity(), this.getMaxAcceleration());
				//				te.getTrajectory().setDTs(dts);
				//				te.updateDuration();
				//				System.out.println("<< done computing DTs");

				//Start parking meets driving, so that driving does not start before parking is finished
				AllenIntervalConstraint meets = new AllenIntervalConstraint(AllenIntervalConstraint.Type.Meets);
				meets.setFrom(startParking);
				meets.setTo(te);
				consToAdd.add(meets);

				if (!solver.addConstraints(consToAdd.toArray(new Constraint[consToAdd.size()]))) {
					metaCSPLogger.severe("ERROR: Could not add constriants " + consToAdd);
					throw new Error("Could not add constriants " + consToAdd);						
				}

				envelopesToTrack.add(te);
			}
		}
		
		return true;
	}

	/**
	 * Sets up a GUI which shows the current status of robots.
	 */
	public void setVisualization(FleetVisualization viz) {
		this.viz = viz;
	}

	private void setPriorityOfEDT(final int prio) {
		try {
			SwingUtilities.invokeAndWait(new Runnable() {
				public void run() {
					Thread.currentThread().setPriority(prio);
				}});
		}
		catch (InvocationTargetException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}
		catch (InterruptedException e2) {
			// TODO Auto-generated catch block
			e2.printStackTrace();
		}		
	}

	/**
	 * Get the current {@link TrajectoryEnvelope} of a robot.
	 * @param robotID The robotID.
	 * @return The current {@link TrajectoryEnvelope} of a robot.
	 */
	public TrajectoryEnvelope getCurrentTrajectoryEnvelope(int robotID) {
		return trackers.get(robotID).getTrajectoryEnvelope();
	}

	protected String[] getStatistics() {
		synchronized (trackers) {
			String CONNECTOR_BRANCH = (char)0x251C + "" + (char)0x2500 + " ";
			String CONNECTOR_LEAF = (char)0x2514 + "" + (char)0x2500 + " ";
			ArrayList<String> ret = new ArrayList<String>();
			int numVar = solver.getConstraintNetwork().getVariables().length;
			int numCon = solver.getConstraintNetwork().getConstraints().length;
			ret.add("Status @ "  + getCurrentTimeInMillis() + " ms");

			ret.add(CONNECTOR_BRANCH + "Eff period ..... " + EFFECTIVE_CONTROL_PERIOD + " ms");
			ret.add(CONNECTOR_BRANCH + "Network ........ " + numVar + " variables, " + numCon + " constriants");
			HashSet<Integer> allRobots = new HashSet<Integer>();
			for (Integer robotID : trackers.keySet()) {
				allRobots.add(robotID);
			}
			String st = CONNECTOR_BRANCH + "Robots ......... ";
			for (Integer robotID : allRobots) {
				AbstractTrajectoryEnvelopeTracker tracker = trackers.get(robotID);
				RobotReport rr = tracker.getRobotReport(); 
				int currentPP = rr.getPathIndex();
				st += tracker.te.getComponent();
				if (tracker instanceof TrajectoryEnvelopeTrackerDummy) st += " (P)";
				else st += " (D)";
				st += ": " + currentPP + "   ";
			}
			ret.add(st);
			ret.add(CONNECTOR_LEAF + "Dependencies ... " + currentDependencies);
			return ret.toArray(new String[ret.size()]);
		}
	}

	protected void overlayStatistics() {
		String[] stats = getStatistics();
		System.out.printf(((char) 0x1b) + "[H");
		System.out.printf(((char) 0x1b) + "[1m");
		for (int l = 0; l < stats.length; l++) {
			System.out.printf(((char) 0x1b) + "[1B" + ((char) 0x1b) + "[2K\r" + stats[l]);
		}
		System.out.printf(((char) 0x1b) + "[0m");
		System.out.printf(((char) 0x1b) + "[200B\r");
	}

	//Print some statistics
	protected void printStatistics() {
		for (String s : getStatistics()) {
			metaCSPLogger.info(s);
		}		
	}

	protected void setupInferenceCallback() {

		Thread inference = new Thread("Coordinator inference") {
			private long threadLastUpdate = Calendar.getInstance().getTimeInMillis();
			@Override
			public void run() {
				while (true) {
					synchronized(solver) {						
						if (!quiet) printStatistics();
						if (overlay) overlayStatistics();
						updateDependencies();
					}

					//Sleep a little...
					if (CONTROL_PERIOD > 0) {
						try { Thread.sleep(CONTROL_PERIOD); }
						catch (InterruptedException e) { e.printStackTrace(); }
					}

					long threadCurrentUpdate = Calendar.getInstance().getTimeInMillis();
					EFFECTIVE_CONTROL_PERIOD = (int)(threadCurrentUpdate-threadLastUpdate);
					threadLastUpdate = threadCurrentUpdate;
					
					if (inferenceCallback != null) inferenceCallback.performOperation();

				}
			}
		};
		inference.setPriority(Thread.MAX_PRIORITY);
		inference.start();
	}

	/**
	 * Factory method that returns a trajectory envelope tracker, which is the class implementing the
	 * interface with real or simulated robots.
	 * @param te The reference {@link TrajectoryEnvelope} that should be driven. 
	 * @param cb A callback that is called every tracking period.
	 * @return An instance of a trajectory envelope tracker.
	 */
	public abstract AbstractTrajectoryEnvelopeTracker getNewTracker(TrajectoryEnvelope te, TrackingCallback cb);

	/**
	 * Determine if a robot is free to accept a new mission (that is, the robot is in state WAITING_FOR_TASK).
	 * @param robotID The ID of the robot.
	 * @return <code>true</code> iff the robot is free to accept a new mission (that is, the robot is in state WAITING_FOR_TASK).
	 */
	public boolean isFree(int robotID) {
		if (muted.contains(robotID)) return false;
		for (TrajectoryEnvelope te : envelopesToTrack) if (te.getRobotID() == robotID) return false;
		AbstractTrajectoryEnvelopeTracker tracker = trackers.get(robotID);
		if (!(tracker instanceof TrajectoryEnvelopeTrackerDummy)) return false;
		TrajectoryEnvelopeTrackerDummy trackerDummy = (TrajectoryEnvelopeTrackerDummy)tracker;
		return (!trackerDummy.isParkingFinished());
	}

	private static void printLicense() {
		System.out.println("\n"+TrajectoryEnvelopeCoordinator.TITLE);
		System.out.println(TrajectoryEnvelopeCoordinator.COPYRIGHT+"\n");
		if (TrajectoryEnvelopeCoordinator.LICENSE != null) {
			List<String> lic = StringUtils.fitWidth(TrajectoryEnvelopeCoordinator.PRIVATE_LICENSE, 72, 5);
			for (String st : lic) System.out.println(st);
		}
		else {
			List<String> lic = StringUtils.fitWidth(TrajectoryEnvelopeCoordinator.PUBLIC_LICENSE, 72, 5);
			for (String st : lic) System.out.println(st);
		}
		System.out.println();
	}

}
