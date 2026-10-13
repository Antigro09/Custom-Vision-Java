package org.customvision.controls;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.customvision.controls.DriveRequests.Bindings;
import org.customvision.controls.DriveRequests.Context;
import org.customvision.controls.DriveRequests.FieldBounds;
import org.customvision.controls.DriveRequests.FrameIdentity;
import org.customvision.controls.DriveRequests.GoalKind;
import org.customvision.controls.DriveRequests.MotionLimits;
import org.customvision.controls.DriveRequests.Outcome;
import org.customvision.controls.DriveRequests.PersistentGoal;
import org.customvision.controls.DriveRequests.Plan;
import org.customvision.controls.DriveRequests.PlanEvidence;
import org.customvision.controls.DriveRequests.PlanningResult;
import org.customvision.controls.DriveRequests.Pose2;
import org.customvision.controls.DriveRequests.Prepared;
import org.customvision.controls.DriveRequests.Reason;
import org.customvision.controls.DriveRequests.RobotRelativeSpeeds;
import org.customvision.controls.DriveRequests.RobotState;
import org.customvision.controls.DriveRequests.SelectionEvidence;
import org.customvision.controls.DriveRequests.ValidationBounds;

/** CPU-only adapter-contract tests. Fake planner results do not claim any physical feasibility. */
public final class DriveRequestsTests {
  private DriveRequestsTests() {}
  private static int assertions;
  private static final long START = 1_000_000_000L;
  private static final FrameIdentity FRAME = new FrameIdentity("test-robot-epoch", "fixed-origin-nwu",
      "reset-4", "world-8");
  private static final Pose2 TARGET = new Pose2(3, 2, Math.PI / 2);
  private static final Context CONTEXT = new Context(FRAME, new FieldBounds(0, 16, 0, 8),
      new MotionLimits(2, 1, 2, 1, 0.4),
      new ValidationBounds(20_000_000L, 80_000_000L, 50_000_000L, 12, 30, 256, 8,
          0.1, 0.1, 0.2, 0.2));
  private static final class Rig {
    long now = START;
    FrameIdentity frame = FRAME;
    Pose2 pose = new Pose2(1, 1, 0);
    RobotRelativeSpeeds speeds = new RobotRelativeSpeeds(0.2, 0.1, 0.1);
    long stateLag;
    boolean cancelled, possession;
    int planningCalls, followerCalls, driveCalls, factoryCalls;
    DriveRequests.TrajectoryPlanner<String> planner = this::plan;
    DriveRequests.StateProvider stateProvider = this::snapshot;
    RobotState snapshot(long sampleTime) { return new RobotState(pose, speeds, frame, sampleTime - stateLag); }
    PlanningResult<String> plan(DriveRequests.GoalRequest request, RobotState state) {
      planningCalls++;
      check(state.measuredSpeeds().equals(speeds), "planner receives measured speeds");
      check(request.context().motionLimits().equals(CONTEXT.motionLimits()), "planner receives explicit limits");
      return PlanningResult.planned(new Plan<>("opaque-test-trajectory", evidence(request, state, now,
          12, 2, 3, 0.6, true, true, true)));
    }
    Bindings<String> bindings() {
      return new Bindings<>(stateProvider, planner,
          (trajectory, state, time) -> { followerCalls++; return RobotRelativeSpeeds.stopped(); },
          new DriveRequests.DrivePort() {
            @Override public void apply(RobotRelativeSpeeds value, MotionLimits limits) { driveCalls++; }
            @Override public void stop() { driveCalls++; }
          }, () -> now, () -> cancelled, () -> possession);
    }
    PersistentGoal selected(GoalKind kind, List<String> ids) {
      return new PersistentGoal(kind, TARGET, selection(FRAME, now, now - 1_000_000L, now,
          true, 0.6, ids));
    }
    Outcome<Prepared<String>> pose() { return DriveRequests.prepareDriveToPose("request-a", TARGET, CONTEXT, bindings()); }
  }

  public static void main(String[] args) {
    normalPoseAndFactory();
    persistentSelection();
    unsafeSelections();
    missingAndFailedCallbacks();
    timeFrameAndMotion();
    slowFinalCallbacks();
    boundedPlans();
    constructorBounds();
    NoMotionExample.run();
    System.out.println("DriveRequestsTests passed " + assertions + " assertions; no hardware or scheduler invoked");
  }

  private static void normalPoseAndFactory() {
    Rig rig = new Rig();
    Prepared<String> prepared = rig.pose().orElseThrow();
    check(prepared.request().kind() == GoalKind.POSE, "pose kind");
    check(prepared.request().selection().isEmpty(), "pose goal has no fabricated tracker selection");
    check(prepared.planningState().measuredSpeeds().equals(rig.speeds), "plan start retains measured speeds");
    check(prepared.plan().trajectory().equals("opaque-test-trajectory"), "actual opaque trajectory retained");
    check(rig.planningCalls == 1, "planner called once");
    Bindings<String> live = rig.bindings();
    Object command = new Object();
    Outcome<Object> built = DriveRequests.constructCommand(prepared, live, (accepted, bindings) -> {
      rig.factoryCalls++;
      check(accepted == prepared && bindings == live, "factory receives exact accepted request and live ports");
      return command;
    });
    check(built.orElseThrow() == command && rig.factoryCalls == 1, "caller command constructed once");
    check(rig.driveCalls == 0 && rig.followerCalls == 0, "preparation and construction neither drive nor follow");
    check(DriveRequests.validateBeforeStart(prepared, live).isEmpty(), "fresh prepared plan can start");
    rig.cancelled = true;
    reason(DriveRequests.constructCommand(prepared, live, (p, b) -> command), Reason.CANCELLED);
    check(rig.factoryCalls == 1 && rig.driveCalls == 0, "cancellation is checked before factory; no implicit stop actuator call");

    Rig slow = new Rig();
    Prepared<String> slowPlan = slow.pose().orElseThrow();
    reason(DriveRequests.constructCommand(slowPlan, slow.bindings(), (p, b) -> {
      slow.now += 51_000_000L;
      return command;
    }), Reason.PLAN_STALE);
    check(slow.driveCalls == 0, "slow construction discards unscheduled command without actuating");
  }

  private static void persistentSelection() {
    Rig rig = new Rig();
    PersistentGoal object = rig.selected(GoalKind.SELECTED_OBJECT, List.of("world-8/object-17"));
    Prepared<String> selected = DriveRequests.prepareDriveToSelectedObject("object", object, CONTEXT, rig.bindings()).orElseThrow();
    check(selected.request().selection().orElseThrow() == object.evidence(), "exact source/world selection evidence retained");
    check(selected.request().goal().equals(object.approachPose()), "externally computed safe approach pose retained");
    rig.possession = true;
    reason(DriveRequests.constructCommand(selected, rig.bindings(), (p, b) -> new Object()), Reason.POSSESSION_PRESENT);
    reason(DriveRequests.prepareDriveToSelectedObject("object", object, CONTEXT, rig.bindings()), Reason.POSSESSION_PRESENT);
    check(rig.pose().accepted(), "pose goal is not interpreted as pickup when possession is present");
    rig.possession = false;
    ArrayList<String> members = new ArrayList<>(List.of("world-8/object-17", "world-8/object-21"));
    PersistentGoal cluster = rig.selected(GoalKind.CLUSTER, members);
    members.add("world-8/object-99");
    Prepared<String> clusterPlan = DriveRequests.prepareDriveToCluster("cluster", cluster, CONTEXT, rig.bindings()).orElseThrow();
    check(clusterPlan.request().selection().orElseThrow().persistentMemberIds().size() == 2, "selection defensively copies immutable persistent IDs");
    check(clusterPlan.request().kind() == GoalKind.CLUSTER, "cluster remains distinct from raw detections");
    reason(DriveRequests.prepareDriveToSelectedObject("wrong", cluster, CONTEXT, rig.bindings()), Reason.WRONG_GOAL_KIND);
    reason(DriveRequests.prepareDriveToCluster("wrong", object, CONTEXT, rig.bindings()), Reason.WRONG_GOAL_KIND);
  }

  private static void unsafeSelections() {
    Rig rig = new Rig();
    selectedReason(rig, GoalKind.CLUSTER, List.of("id-1", "id-1"), true, 0.5, Reason.DUPLICATE_MEMBER);
    selectedReason(rig, GoalKind.CLUSTER, List.of("id-1"), true, 0.5, Reason.SELECTION_UNSAFE);
    selectedReason(rig, GoalKind.SELECTED_OBJECT, List.of("id-1", "id-2"), true, 0.5, Reason.SELECTION_UNSAFE);
    selectedReason(rig, GoalKind.SELECTED_OBJECT, List.of("id-1"), false, 0.5, Reason.SELECTION_UNSAFE);
    selectedReason(rig, GoalKind.SELECTED_OBJECT, List.of("id-1"), true, 0.399, Reason.SELECTION_UNSAFE);
    selectedReason(rig, GoalKind.CLUSTER, List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"), true, 0.5, Reason.TOO_MANY_MEMBERS);
    for (int bad = 0; bad < 4; bad++) {
      long selected = rig.now, observed = rig.now, world = rig.now;
      if (bad == 0) selected -= 80_000_001L;
      if (bad == 1) observed -= 80_000_001L;
      if (bad == 2) world -= 80_000_001L;
      if (bad == 3) world += 1;
      PersistentGoal goal = new PersistentGoal(GoalKind.SELECTED_OBJECT, TARGET,
          selection(FRAME, selected, observed, world, true, 0.6, List.of("persistent-id")));
      reason(DriveRequests.prepareDriveToSelectedObject("aged", goal, CONTEXT, rig.bindings()), Reason.SELECTION_STALE);
    }
    FrameIdentity prior = new FrameIdentity(FRAME.robotEpoch(), FRAME.fieldOrigin(), "reset-3", FRAME.worldRevision());
    PersistentGoal oldReset = new PersistentGoal(GoalKind.CLUSTER, TARGET,
        selection(prior, rig.now, rig.now, rig.now, true, 1, List.of("a", "b")));
    reason(DriveRequests.prepareDriveToCluster("reset", oldReset, CONTEXT, rig.bindings()), Reason.FRAME_MISMATCH);
    check(rig.planningCalls == 0, "unsafe target selection never calls planner");
  }

  private static void missingAndFailedCallbacks() {
    Rig rig = new Rig();
    Bindings<String> good = rig.bindings();
    List<Bindings<String>> missing = List.of(
        new Bindings<>(null, good.planner(), good.follower(), good.drive(), good.robotClockNs(), good.cancelled(), good.possessionPresent()),
        new Bindings<>(good.stateProvider(), null, good.follower(), good.drive(), good.robotClockNs(), good.cancelled(), good.possessionPresent()),
        new Bindings<>(good.stateProvider(), good.planner(), null, good.drive(), good.robotClockNs(), good.cancelled(), good.possessionPresent()),
        new Bindings<>(good.stateProvider(), good.planner(), good.follower(), null, good.robotClockNs(), good.cancelled(), good.possessionPresent()),
        new Bindings<>(good.stateProvider(), good.planner(), good.follower(), good.drive(), null, good.cancelled(), good.possessionPresent()),
        new Bindings<>(good.stateProvider(), good.planner(), good.follower(), good.drive(), good.robotClockNs(), null, good.possessionPresent()),
        new Bindings<>(good.stateProvider(), good.planner(), good.follower(), good.drive(), good.robotClockNs(), good.cancelled(), null));
    for (Bindings<String> incomplete : missing) reason(DriveRequests.prepareDriveToPose("missing", TARGET, CONTEXT, incomplete), Reason.MISSING_BINDING);
    reason(DriveRequests.prepareDriveToPose("missing", TARGET, CONTEXT, null), Reason.MISSING_BINDING);
    reason(DriveRequests.prepareDriveToPose("", TARGET, CONTEXT, good), Reason.INVALID_REQUEST);
    reason(DriveRequests.prepareDriveToPose("null", TARGET, null, good), Reason.INVALID_REQUEST);
    reason(DriveRequests.prepareDriveToSelectedObject("null", null, CONTEXT, good), Reason.INVALID_REQUEST);
    reason(DriveRequests.prepareDriveToCluster("null", null, CONTEXT, good), Reason.INVALID_REQUEST);
    Prepared<String> prepared = rig.pose().orElseThrow();
    reason(DriveRequests.constructCommand(prepared, good, null), Reason.MISSING_BINDING);
    reason(DriveRequests.constructCommand(null, good, (p, b) -> new Object()), Reason.INVALID_REQUEST);
    reason(DriveRequests.constructCommand(prepared, good, (p, b) -> null), Reason.FACTORY_RETURNED_NULL);
    reason(DriveRequests.constructCommand(prepared, good, (p, b) -> { throw new IllegalStateException("callback data must not leak"); }), Reason.CALLBACK_FAILED);
    rig.planner = (request, state) -> { throw new IllegalStateException("callback data must not leak"); };
    Outcome<Prepared<String>> failed = rig.pose();
    reason(failed, Reason.CALLBACK_FAILED);
    check(!failed.rejection().orElseThrow().detail().contains("callback data"), "exception data absent from diagnostics");
    rig.stateProvider = time -> null;
    reason(rig.pose(), Reason.CALLBACK_FAILED);
  }

  private static void timeFrameAndMotion() {
    Rig rig = new Rig();
    rig.cancelled = true; reason(rig.pose(), Reason.CANCELLED);
    check(rig.planningCalls == 0, "cancelled request never plans");
    rig.cancelled = false;
    rig.stateLag = 20_000_001L; reason(rig.pose(), Reason.STATE_STALE);
    rig.stateLag = -1; reason(rig.pose(), Reason.STATE_STALE);
    rig.stateLag = 0;
    rig.pose = new Pose2(-0.01, 1, 0); reason(rig.pose(), Reason.STATE_OUT_OF_BOUNDS);
    rig.pose = new Pose2(1, 1, 0);
    reason(DriveRequests.prepareDriveToPose("bounds", new Pose2(16.01, 1, 0), CONTEXT, rig.bindings()), Reason.GOAL_OUT_OF_BOUNDS);
    rig.frame = new FrameIdentity("other-epoch", FRAME.fieldOrigin(), FRAME.resetIdentity(), FRAME.worldRevision());
    reason(rig.pose(), Reason.FRAME_MISMATCH);
    rig.frame = FRAME;
    Prepared<String> prepared = rig.pose().orElseThrow();
    rig.pose = new Pose2(1.101, 1, 0); reason(DriveRequests.constructCommand(prepared, rig.bindings(), (p, b) -> new Object()), Reason.PLAN_START_CHANGED);
    rig.pose = new Pose2(1, 1, 0.101); check(DriveRequests.validateBeforeStart(prepared, rig.bindings()).orElseThrow().reason() == Reason.PLAN_START_CHANGED, "heading drift before start");
    rig.pose = new Pose2(1, 1, 0);
    rig.speeds = new RobotRelativeSpeeds(0.401, 0.1, 0.1); reason(DriveRequests.constructCommand(prepared, rig.bindings(), (p, b) -> new Object()), Reason.PLAN_START_CHANGED);
    rig.speeds = new RobotRelativeSpeeds(0.2, 0.1, 0.301); reason(DriveRequests.constructCommand(prepared, rig.bindings(), (p, b) -> new Object()), Reason.PLAN_START_CHANGED);
    rig.speeds = prepared.planningState().measuredSpeeds();
    rig.frame = new FrameIdentity(FRAME.robotEpoch(), FRAME.fieldOrigin(), "reset-5", FRAME.worldRevision());
    reason(DriveRequests.constructCommand(prepared, rig.bindings(), (p, b) -> new Object()), Reason.FRAME_MISMATCH);
    rig.frame = FRAME;
    rig.now = START - 1; reason(DriveRequests.constructCommand(prepared, rig.bindings(), (p, b) -> new Object()), Reason.CLOCK_INVALID);
    rig.now = -1; reason(rig.pose(), Reason.CLOCK_INVALID);
    rig.now = START;
    rig.planner = (request, state) -> { rig.now--; return PlanningResult.planned(new Plan<>("x", evidence(request, state, START, 2, 1, 1, 1, true, true, true))); };
    reason(rig.pose(), Reason.CLOCK_INVALID);

    Rig delayed = new Rig();
    delayed.planner = (request, state) -> {
      delayed.now += 80_000_001L;
      return PlanningResult.planned(new Plan<>("x", evidence(request, state, delayed.now, 2, 1, 1, 1, true, true, true)));
    };
    reason(DriveRequests.prepareDriveToSelectedObject("delayed", delayed.selected(GoalKind.SELECTED_OBJECT, List.of("id")), CONTEXT, delayed.bindings()), Reason.SELECTION_STALE);
    check(delayed.driveCalls == 0, "planning delay cannot actuate or hide stale selection");

    // Yaw comparison wraps at +/-pi and never uses alliance-relative axes.
    Rig wrapping = new Rig(); wrapping.pose = new Pose2(1, 1, Math.PI - 0.01);
    Prepared<String> wrap = wrapping.pose().orElseThrow(); wrapping.pose = new Pose2(1, 1, -Math.PI + 0.01);
    check(DriveRequests.validateBeforeStart(wrap, wrapping.bindings()).isEmpty(), "small fixed-origin yaw drift across +/-pi is valid");
  }

  private static void boundedPlans() {
    Rig rig = new Rig();
    rig.planner = (request, state) -> PlanningResult.rejected("NO_PATH"); reason(rig.pose(), Reason.PLAN_REJECTED);
    rig.planner = (request, state) -> null; reason(rig.pose(), Reason.PLAN_REJECTED);
    for (int bad = 0; bad < 7; bad++) {
      final int mode = bad;
      rig.planner = (request, state) -> PlanningResult.planned(new Plan<>("actual-opaque-trajectory",
          evidence(request, state, rig.now, mode == 0 ? 1 : mode == 1 ? 257 : 4,
              mode == 2 ? 12.01 : 1, mode == 3 ? 30.01 : 1, mode == 4 ? 0.399 : 1,
              mode != 5, true, mode != 6)));
      reason(rig.pose(), bad < 4 ? Reason.PLAN_TOO_LARGE : Reason.PLAN_UNSAFE);
    }
    rig.planner = (request, state) -> PlanningResult.planned(new Plan<>("trajectory",
        new PlanEvidence("another-request", FRAME, TARGET, state, CONTEXT.motionLimits(), CONTEXT.fieldBounds(), rig.now, 2, 1, 1, 1, true, true, true)));
    reason(rig.pose(), Reason.PLAN_MISMATCH);
    rig.planner = (request, state) -> PlanningResult.planned(new Plan<>("trajectory",
        new PlanEvidence(request.requestId(), FRAME, new Pose2(4, 2, 0), state, CONTEXT.motionLimits(), CONTEXT.fieldBounds(), rig.now, 2, 1, 1, 1, true, true, true)));
    reason(rig.pose(), Reason.PLAN_MISMATCH);
    rig.planner = (request, state) -> PlanningResult.planned(new Plan<>("trajectory",
        new PlanEvidence(request.requestId(), FRAME, TARGET,
            new RobotState(state.pose(), RobotRelativeSpeeds.stopped(), state.frame(), state.sampledRobotNs()),
            CONTEXT.motionLimits(), CONTEXT.fieldBounds(), rig.now, 2, 1, 1, 1, true, true, true)));
    reason(rig.pose(), Reason.PLAN_MISMATCH);
    rig.planner = (request, state) -> PlanningResult.planned(new Plan<>("trajectory",
        new PlanEvidence(request.requestId(), FRAME, TARGET, state,
            new MotionLimits(10, 10, 10, 10, 0), CONTEXT.fieldBounds(), rig.now, 2, 1, 1, 1, true, true, true)));
    reason(rig.pose(), Reason.PLAN_MISMATCH);
    rig.planner = (request, state) -> PlanningResult.planned(new Plan<>("trajectory",
        new PlanEvidence(request.requestId(), FRAME, TARGET, state, CONTEXT.motionLimits(),
            new FieldBounds(-10, 30, -10, 30), rig.now, 2, 1, 1, 1, true, true, true)));
    reason(rig.pose(), Reason.PLAN_MISMATCH);
    rig.planner = (request, state) -> PlanningResult.planned(new Plan<>("trajectory", evidence(request, state, rig.now + 1, 2, 1, 1, 1, true, true, true)));
    reason(rig.pose(), Reason.PLAN_STALE);
    rig.planner = (request, state) -> {
      long plannedAt = rig.now; rig.now += 50_000_001L;
      return PlanningResult.planned(new Plan<>("trajectory", evidence(request, state, plannedAt, 2, 1, 1, 1, true, true, true)));
    };
    reason(rig.pose(), Reason.PLAN_STALE);
  }

  private static void slowFinalCallbacks() {
    Rig preparation = new Rig();
    int[] snapshots = {0};
    preparation.stateProvider = time -> {
      RobotState snapshot = preparation.snapshot(time);
      if (++snapshots[0] == 3) preparation.now += 50_000_001L;
      return snapshot;
    };
    reason(preparation.pose(), Reason.PLAN_STALE);
    check(snapshots[0] == 3, "preparation rejects a slow final state callback at its return time");

    Rig stateOnly = new Rig();
    Prepared<String> statePlan = stateOnly.pose().orElseThrow();
    stateOnly.stateProvider = time -> {
      RobotState snapshot = stateOnly.snapshot(time);
      stateOnly.now += 20_000_001L;
      return snapshot;
    };
    check(DriveRequests.validateBeforeStart(statePlan, stateOnly.bindings()).orElseThrow().reason() == Reason.STATE_STALE,
        "state age uses completion time even while plan is still fresh");

    Rig beforeFactory = new Rig();
    Prepared<String> beforePlan = beforeFactory.pose().orElseThrow();
    beforeFactory.stateProvider = time -> {
      RobotState snapshot = beforeFactory.snapshot(time);
      beforeFactory.now += 50_000_001L;
      return snapshot;
    };
    reason(DriveRequests.constructCommand(beforePlan, beforeFactory.bindings(), (p, b) -> {
      beforeFactory.factoryCalls++;
      return new Object();
    }), Reason.PLAN_STALE);
    check(beforeFactory.factoryCalls == 0, "expired final callback rejects before constructing command");

    Rig afterFactory = new Rig();
    Prepared<String> afterPlan = afterFactory.pose().orElseThrow();
    boolean[] constructed = {false};
    afterFactory.stateProvider = time -> {
      RobotState snapshot = afterFactory.snapshot(time);
      if (constructed[0]) afterFactory.now += 50_000_001L;
      return snapshot;
    };
    reason(DriveRequests.constructCommand(afterPlan, afterFactory.bindings(), (p, b) -> {
      constructed[0] = true;
      return new Object();
    }), Reason.PLAN_STALE);
    check(constructed[0] && afterFactory.driveCalls == 0, "post-factory final callback rejects unscheduled result without actuating");

    Rig cancelledSensor = new Rig();
    Prepared<String> sensorPlan = cancelledSensor.pose().orElseThrow();
    Bindings<String> sensorPorts = cancelledSensor.bindings();
    Bindings<String> slowCancellation = new Bindings<>(sensorPorts.stateProvider(), sensorPorts.planner(),
        sensorPorts.follower(), sensorPorts.drive(), sensorPorts.robotClockNs(),
        () -> { cancelledSensor.now += 50_000_001L; return false; }, sensorPorts.possessionPresent());
    check(DriveRequests.validateBeforeStart(sensorPlan, slowCancellation).orElseThrow().reason() == Reason.PLAN_STALE,
        "slow cancellation callback cannot accept already expired plan");

    Rig possessionSensor = new Rig();
    Prepared<String> objectPlan = DriveRequests.prepareDriveToSelectedObject("sensor-object",
        possessionSensor.selected(GoalKind.SELECTED_OBJECT, List.of("persistent-id")), CONTEXT,
        possessionSensor.bindings()).orElseThrow();
    Bindings<String> objectPorts = possessionSensor.bindings();
    Bindings<String> slowPossession = new Bindings<>(objectPorts.stateProvider(), objectPorts.planner(),
        objectPorts.follower(), objectPorts.drive(), objectPorts.robotClockNs(), objectPorts.cancelled(),
        () -> { possessionSensor.now += 80_000_001L; return false; });
    check(DriveRequests.validateBeforeStart(objectPlan, slowPossession).orElseThrow().reason() == Reason.SELECTION_STALE,
        "slow possession callback cannot accept expired object selection");

    Rig rolledBack = new Rig();
    Prepared<String> rollbackPlan = rolledBack.pose().orElseThrow();
    rolledBack.stateProvider = time -> {
      RobotState snapshot = rolledBack.snapshot(time);
      rolledBack.now--;
      return snapshot;
    };
    check(DriveRequests.validateBeforeStart(rollbackPlan, rolledBack.bindings()).orElseThrow().reason() == Reason.CLOCK_INVALID,
        "clock rollback in final callback rejects before-start validation");

    Rig prepareRollback = new Rig();
    int[] calls = {0};
    prepareRollback.stateProvider = time -> {
      RobotState snapshot = prepareRollback.snapshot(time);
      if (++calls[0] == 3) prepareRollback.now--;
      return snapshot;
    };
    reason(prepareRollback.pose(), Reason.CLOCK_INVALID);
  }

  private static void constructorBounds() {
    invalid(() -> new Pose2(Double.NaN, 1, 0));
    invalid(() -> new RobotRelativeSpeeds(1, Double.POSITIVE_INFINITY, 0));
    invalid(() -> new FrameIdentity("", "nwu", "r", "w"));
    invalid(() -> new FieldBounds(1, 1, 0, 1));
    invalid(() -> new MotionLimits(0, 1, 1, 1, 0));
    invalid(() -> new ValidationBounds(1, 1, 1, 1, 1, 100_001, 2, 0, 0, 0, 0));
    invalid(() -> new PlanningResult<String>(Optional.empty(), Optional.empty()));
    invalid(() -> new Outcome<String>(Optional.of("a"), Optional.of(new DriveRequests.Rejection(Reason.CANCELLED, "x"))));
    invalid(() -> new PersistentGoal(GoalKind.POSE, TARGET, selection(FRAME, START, START, START, true, 1, List.of("a"))));
    invalid(() -> new Plan<String>(null, new PlanEvidence("a", FRAME, TARGET, new Rig().snapshot(START), CONTEXT.motionLimits(), CONTEXT.fieldBounds(), START, 2, 1, 1, 1, true, true, true)));
    ArrayList<String> tooLarge = new ArrayList<>();
    for (int i = 0; i < 1025; i++) tooLarge.add("id-" + i);
    invalid(() -> selection(FRAME, START, START, START, true, 1, tooLarge));
  }

  private static SelectionEvidence selection(FrameIdentity frame, long selected, long observed,
      long world, boolean reachable, double clearance, List<String> members) {
    return new SelectionEvidence(frame, "source-set-revision-5", "selector-revision-9", selected,
        observed, world, reachable, clearance, members);
  }
  private static PlanEvidence evidence(DriveRequests.GoalRequest request, RobotState state, long now, int samples,
      double duration, double length, double clearance, boolean reachable, boolean inBounds, boolean limits) {
    return new PlanEvidence(request.requestId(), request.context().frame(), request.goal(), state, request.context().motionLimits(), request.context().fieldBounds(), now,
        samples, duration, length, clearance, reachable, inBounds, limits);
  }
  private static void selectedReason(Rig rig, GoalKind kind, List<String> ids,
      boolean reachable, double clearance, Reason expected) {
    PersistentGoal goal = new PersistentGoal(kind, TARGET,
        selection(FRAME, rig.now, rig.now, rig.now, reachable, clearance, ids));
    Outcome<Prepared<String>> result = kind == GoalKind.CLUSTER
        ? DriveRequests.prepareDriveToCluster("unsafe", goal, CONTEXT, rig.bindings())
        : DriveRequests.prepareDriveToSelectedObject("unsafe", goal, CONTEXT, rig.bindings());
    reason(result, expected);
  }
  private static void reason(Outcome<?> outcome, Reason expected) {
    check(!outcome.accepted() && outcome.rejection().orElseThrow().reason() == expected,
        "expected " + expected + ", got " + outcome.rejection());
  }
  private static void invalid(Runnable operation) {
    try { operation.run(); throw new AssertionError("invalid construction succeeded"); }
    catch (IllegalArgumentException | NullPointerException expected) { assertions++; }
  }
  private static void check(boolean condition, String label) { assertions++; if (!condition) throw new AssertionError(label); }
}
