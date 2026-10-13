package org.customvision.controls;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Validated requests for an application-owned planner and command factory.
 *
 * <p>This class has no scheduler, follower implementation, target selector, or drivetrain
 * implementation. Coordinates are canonical fixed-origin field NWU meters and yaw radians;
 * velocities are explicitly robot relative. World-State must supply selected persistent goals.
 * Preparing a request and constructing a command never call {@link DrivePort#apply} or
 * {@link DrivePort#stop}. The injected factory must construct without actuating or scheduling.
 */
public final class DriveRequests {
  private DriveRequests() {}

  public enum GoalKind { POSE, SELECTED_OBJECT, CLUSTER }

  public enum Reason {
    MISSING_BINDING, INVALID_REQUEST, CANCELLED, POSSESSION_PRESENT, CALLBACK_FAILED,
    CLOCK_INVALID, FRAME_MISMATCH, STATE_STALE, STATE_OUT_OF_BOUNDS,
    GOAL_OUT_OF_BOUNDS, SELECTION_STALE, SELECTION_UNSAFE, WRONG_GOAL_KIND,
    DUPLICATE_MEMBER, TOO_MANY_MEMBERS, PLAN_REJECTED, PLAN_MISMATCH,
    PLAN_STALE, PLAN_UNSAFE, PLAN_TOO_LARGE, PLAN_START_CHANGED, FACTORY_RETURNED_NULL
  }

  /** A bounded diagnostic. Callback exception text is deliberately not copied into it. */
  public record Rejection(Reason reason, String detail) {
    public Rejection {
      Objects.requireNonNull(reason);
      text(detail, "diagnostic", 256);
    }
  }

  public record Outcome<T>(Optional<T> value, Optional<Rejection> rejection) {
    public Outcome {
      Objects.requireNonNull(value);
      Objects.requireNonNull(rejection);
      if (value.isPresent() == rejection.isPresent()) {
        throw new IllegalArgumentException("outcome must contain exactly one value or rejection");
      }
    }
    public boolean accepted() { return value.isPresent(); }
    public T orElseThrow() { return value.orElseThrow(() -> new IllegalStateException(rejection.orElseThrow().toString())); }
  }

  /** Fixed-origin field position and NWU yaw. No alliance-relative interpretation is applied. */
  public record Pose2(double xMeters, double yMeters, double yawRadians) {
    public Pose2 { finite(xMeters, "x"); finite(yMeters, "y"); finite(yawRadians, "yaw"); }
  }

  /** Robot-relative forward, left, and counterclockwise angular speeds. */
  public record RobotRelativeSpeeds(double forwardMetersPerSecond, double leftMetersPerSecond,
                                    double counterclockwiseRadiansPerSecond) {
    public RobotRelativeSpeeds {
      finite(forwardMetersPerSecond, "forward speed");
      finite(leftMetersPerSecond, "left speed");
      finite(counterclockwiseRadiansPerSecond, "angular speed");
    }
    public static RobotRelativeSpeeds stopped() { return new RobotRelativeSpeeds(0, 0, 0); }
  }

  /** Explicitly identifies the monotonic clock, fixed field origin, pose reset, and world map. */
  public record FrameIdentity(String robotEpoch, String fieldOrigin, String resetIdentity,
                              String worldRevision) {
    public FrameIdentity {
      text(robotEpoch, "robot epoch", 128); text(fieldOrigin, "field origin", 128);
      text(resetIdentity, "reset identity", 128); text(worldRevision, "world revision", 128);
    }
  }

  public record RobotState(Pose2 pose, RobotRelativeSpeeds measuredSpeeds,
                           FrameIdentity frame, long sampledRobotNs) {
    public RobotState {
      Objects.requireNonNull(pose); Objects.requireNonNull(measuredSpeeds); Objects.requireNonNull(frame);
      nonnegative(sampledRobotNs, "state time");
    }
  }

  public record FieldBounds(double minX, double maxX, double minY, double maxY) {
    public FieldBounds {
      finite(minX, "min x"); finite(maxX, "max x"); finite(minY, "min y"); finite(maxY, "max y");
      if (minX >= maxX || minY >= maxY) throw new IllegalArgumentException("empty field bounds");
    }
    public boolean contains(Pose2 pose) {
      return pose.xMeters() >= minX && pose.xMeters() <= maxX
          && pose.yMeters() >= minY && pose.yMeters() <= maxY;
    }
  }

  /** Caller-configured motion bounds; no motor or mechanism constants are supplied here. */
  public record MotionLimits(double maximumSpeedMetersPerSecond,
                             double maximumAccelerationMetersPerSecondSquared,
                             double maximumAngularSpeedRadiansPerSecond,
                             double maximumAngularAccelerationRadiansPerSecondSquared,
                             double minimumClearanceMeters) {
    public MotionLimits {
      positive(maximumSpeedMetersPerSecond, "maximum speed");
      positive(maximumAccelerationMetersPerSecondSquared, "maximum acceleration");
      positive(maximumAngularSpeedRadiansPerSecond, "maximum angular speed");
      positive(maximumAngularAccelerationRadiansPerSecondSquared, "maximum angular acceleration");
      nonnegativeFinite(minimumClearanceMeters, "minimum clearance");
    }
  }

  /** Explicit age/work bounds and tolerances for drift while the caller computes a plan. */
  public record ValidationBounds(long maximumStateAgeNs, long maximumSelectionAgeNs,
                                 long maximumPlanAgeNs, double maximumPathDurationSeconds,
                                 double maximumPathLengthMeters, int maximumPlanSamples,
                                 int maximumClusterMembers, double maximumStartPositionDriftMeters,
                                 double maximumStartHeadingDriftRadians,
                                 double maximumStartLinearSpeedDriftMetersPerSecond,
                                 double maximumStartAngularSpeedDriftRadiansPerSecond) {
    public ValidationBounds {
      positive(maximumStateAgeNs, "state age"); positive(maximumSelectionAgeNs, "selection age");
      positive(maximumPlanAgeNs, "plan age"); positive(maximumPathDurationSeconds, "path duration");
      positive(maximumPathLengthMeters, "path length");
      if (maximumPlanSamples < 2 || maximumPlanSamples > 100_000) throw new IllegalArgumentException("plan sample bound");
      if (maximumClusterMembers < 2 || maximumClusterMembers > 1024) throw new IllegalArgumentException("cluster member bound");
      nonnegativeFinite(maximumStartPositionDriftMeters, "position drift");
      nonnegativeFinite(maximumStartHeadingDriftRadians, "heading drift");
      nonnegativeFinite(maximumStartLinearSpeedDriftMetersPerSecond, "linear speed drift");
      nonnegativeFinite(maximumStartAngularSpeedDriftRadiansPerSecond, "angular speed drift");
    }
  }

  public record Context(FrameIdentity frame, FieldBounds fieldBounds, MotionLimits motionLimits,
                        ValidationBounds validationBounds) {
    public Context {
      Objects.requireNonNull(frame); Objects.requireNonNull(fieldBounds);
      Objects.requireNonNull(motionLimits); Objects.requireNonNull(validationBounds);
    }
  }

  /**
   * Evidence from the existing World-State selector, using globally unique persistent track IDs.
   * Each ID must include whatever source/boot/world identity the owning tracker needs; camera-local
   * track numbers and raw detection counts are insufficient. sourceRevision identifies the source
   * set and its revisions; selectionRevision identifies the externally owned selection decision.
   * All times are in frame.robotEpoch. Clearance includes the configured robot footprint.
   */
  public record SelectionEvidence(FrameIdentity frame, String sourceRevision, String selectionRevision,
                                   long selectedRobotNs, long oldestMemberObservationRobotNs,
                                   long worldSnapshotRobotNs, boolean reachable,
                                   double minimumApproachClearanceMeters, List<String> persistentMemberIds) {
    public SelectionEvidence {
      Objects.requireNonNull(frame); text(sourceRevision, "source revision", 128);
      text(selectionRevision, "selection revision", 128);
      nonnegative(selectedRobotNs, "selection time");
      nonnegative(oldestMemberObservationRobotNs, "observation time");
      nonnegative(worldSnapshotRobotNs, "world snapshot time");
      nonnegativeFinite(minimumApproachClearanceMeters, "approach clearance");
      Objects.requireNonNull(persistentMemberIds);
      if (persistentMemberIds.size() > 1024) throw new IllegalArgumentException("absolute member bound exceeded");
      persistentMemberIds = List.copyOf(persistentMemberIds);
      for (String id : persistentMemberIds) text(id, "persistent track ID", 256);
    }
  }

  /** The approach pose is chosen by World-State, including intake geometry and safe approach. */
  public record PersistentGoal(GoalKind kind, Pose2 approachPose, SelectionEvidence evidence) {
    public PersistentGoal {
      Objects.requireNonNull(kind); Objects.requireNonNull(approachPose); Objects.requireNonNull(evidence);
      if (kind == GoalKind.POSE) throw new IllegalArgumentException("persistent goal needs object or cluster kind");
    }
  }

  public record GoalRequest(String requestId, GoalKind kind, Pose2 goal, Context context,
                            long requestedRobotNs, Optional<SelectionEvidence> selection) {
    public GoalRequest {
      text(requestId, "request ID", 128); Objects.requireNonNull(kind); Objects.requireNonNull(goal);
      Objects.requireNonNull(context); Objects.requireNonNull(selection); nonnegative(requestedRobotNs, "request time");
      if ((kind == GoalKind.POSE) == selection.isPresent()) throw new IllegalArgumentException("selection and goal kind disagree");
    }
  }

  /**
   * Assertions supplied by the real planner/validator; this library cannot inspect an opaque
   * trajectory's collision or dynamics model. False, missing, mismatched, stale or excessive
   * evidence rejects the request. A success label is never substituted for a computed trajectory.
   */
  public record PlanEvidence(String requestId, FrameIdentity frame, Pose2 plannedGoal,
                             RobotState plannedInitialState, MotionLimits appliedMotionLimits,
                             FieldBounds appliedFieldBounds, long plannedRobotNs,
                             int sampleCount, double durationSeconds,
                             double pathLengthMeters, double minimumClearanceMeters,
                             boolean reachable, boolean staysWithinFieldBounds,
                             boolean satisfiesMotionLimits) {
    public PlanEvidence {
      text(requestId, "plan request ID", 128); Objects.requireNonNull(frame); Objects.requireNonNull(plannedGoal);
      Objects.requireNonNull(plannedInitialState); Objects.requireNonNull(appliedMotionLimits);
      Objects.requireNonNull(appliedFieldBounds);
      nonnegative(plannedRobotNs, "plan time");
      if (sampleCount < 0) throw new IllegalArgumentException("negative samples");
      nonnegativeFinite(durationSeconds, "duration"); nonnegativeFinite(pathLengthMeters, "length");
      nonnegativeFinite(minimumClearanceMeters, "plan clearance");
    }
  }

  public record Plan<T>(T trajectory, PlanEvidence evidence) {
    public Plan { Objects.requireNonNull(trajectory); Objects.requireNonNull(evidence); }
  }

  public record PlanningResult<T>(Optional<Plan<T>> plan, Optional<String> rejectionCode) {
    public PlanningResult {
      Objects.requireNonNull(plan); Objects.requireNonNull(rejectionCode);
      if (plan.isPresent() == rejectionCode.isPresent()) throw new IllegalArgumentException("exactly one plan or rejection required");
      rejectionCode.ifPresent(code -> text(code, "planner rejection", 128));
    }
    public static <T> PlanningResult<T> planned(Plan<T> plan) { return new PlanningResult<>(Optional.of(plan), Optional.empty()); }
    public static <T> PlanningResult<T> rejected(String code) { return new PlanningResult<>(Optional.empty(), Optional.of(code)); }
  }

  @FunctionalInterface public interface StateProvider { RobotState snapshot(long nowRobotNs); }
  @FunctionalInterface public interface TrajectoryPlanner<T> {
    PlanningResult<T> plan(GoalRequest request, RobotState initialState);
  }
  /** Caller-owned follower callback. Produces a demand; it must not actuate hardware itself. */
  @FunctionalInterface public interface FollowerPort<T> {
    RobotRelativeSpeeds desiredSpeeds(T trajectory, RobotState measuredState, long nowRobotNs);
  }
  /** Bound by the robot application; this library never invokes these methods. */
  public interface DrivePort {
    void apply(RobotRelativeSpeeds speeds, MotionLimits limits);
    void stop();
  }

  /**
   * Required explicit bindings. Missing callbacks are reported by preparation, rather than
   * silently binding a stationary robot, constant false possession sensor or no-op drive.
   * Clock and state callbacks must identify the same Context.frame robot monotonic epoch.
   */
  public record Bindings<T>(StateProvider stateProvider, TrajectoryPlanner<T> planner,
                            FollowerPort<T> follower, DrivePort drive, LongSupplier robotClockNs,
                            BooleanSupplier cancelled, BooleanSupplier possessionPresent) {}

  /** Library-created, validated preparation; no caller can directly construct an accepted one. */
  public static final class Prepared<T> {
    private final GoalRequest request;
    private final RobotState planningState;
    private final Plan<T> plan;
    private Prepared(GoalRequest request, RobotState state, Plan<T> plan) {
      this.request = request; planningState = state; this.plan = plan;
    }
    public GoalRequest request() { return request; }
    public RobotState planningState() { return planningState; }
    public Plan<T> plan() { return plan; }
  }

  /**
   * Constructs the caller's command without actuating or scheduling it. The command must retain
   * and check live cancellation, possession, frame/reset, ages, measured state and limits for its
   * entire execution, and stop through its own lifecycle on invalidation/cancellation/completion.
   * This factory is the adapter to the team's existing command scheduler and control owner.
   */
  @FunctionalInterface public interface CommandFactory<T, C> {
    C construct(Prepared<T> prepared, Bindings<T> liveBindings);
  }

  public static <T> Outcome<Prepared<T>> prepareDriveToPose(String requestId, Pose2 goal,
                                                           Context context, Bindings<T> bindings) {
    return prepare(requestId, GoalKind.POSE, goal, Optional.empty(), context, bindings);
  }

  public static <T> Outcome<Prepared<T>> prepareDriveToSelectedObject(String requestId,
      PersistentGoal selected, Context context, Bindings<T> bindings) {
    if (selected == null) return reject(Reason.INVALID_REQUEST, "selected persistent object goal is required");
    if (selected.kind() != GoalKind.SELECTED_OBJECT) return reject(Reason.WRONG_GOAL_KIND, "expected a selected persistent object");
    return prepare(requestId, selected.kind(), selected.approachPose(), Optional.of(selected.evidence()), context, bindings);
  }

  public static <T> Outcome<Prepared<T>> prepareDriveToCluster(String requestId,
      PersistentGoal selected, Context context, Bindings<T> bindings) {
    if (selected == null) return reject(Reason.INVALID_REQUEST, "selected persistent cluster goal is required");
    if (selected.kind() != GoalKind.CLUSTER) return reject(Reason.WRONG_GOAL_KIND, "expected an externally selected cluster");
    return prepare(requestId, selected.kind(), selected.approachPose(), Optional.of(selected.evidence()), context, bindings);
  }

  private static <T> Outcome<Prepared<T>> prepare(String requestId, GoalKind kind, Pose2 goal,
      Optional<SelectionEvidence> selection, Context context, Bindings<T> bindings) {
    Rejection missing = missing(bindings);
    if (missing != null) return rejected(missing);
    if (context == null || goal == null || requestId == null || requestId.isBlank() || requestId.length() > 128) {
      return reject(Reason.INVALID_REQUEST, "bounded request ID, canonical goal and explicit context are required");
    }
    try {
      long now = bindings.robotClockNs().getAsLong();
      if (now < 0) return reject(Reason.CLOCK_INVALID, "negative robot monotonic time");
      GoalRequest request = new GoalRequest(requestId, kind, goal, context, now, selection);
      Rejection problem = cancelled(request, bindings);
      if (problem != null) return rejected(problem);
      if (!context.fieldBounds().contains(goal)) return reject(Reason.GOAL_OUT_OF_BOUNDS, "canonical goal lies outside configured field bounds");
      problem = selection(request, now);
      if (problem != null) return rejected(problem);
      RobotState state = bindings.stateProvider().snapshot(now);
      problem = state(context, state, now);
      if (problem != null) return rejected(problem);
      PlanningResult<T> planned = bindings.planner().plan(request, state);
      if (planned == null) return reject(Reason.PLAN_REJECTED, "planner returned no result");
      if (planned.plan().isEmpty()) return reject(Reason.PLAN_REJECTED, "planner rejected: " + planned.rejectionCode().orElseThrow());
      Plan<T> plan = planned.plan().orElseThrow();
      long after = bindings.robotClockNs().getAsLong();
      if (after < now) return reject(Reason.CLOCK_INVALID, "robot clock regressed during preparation");
      problem = cancelled(request, bindings);
      if (problem == null) problem = selection(request, after);
      if (problem == null) problem = plan(request, state, plan, after);
      if (problem == null) problem = state(context, bindings.stateProvider().snapshot(after), after);
      if (problem != null) return rejected(problem);
      Prepared<T> prepared = new Prepared<>(request, state, plan);
      problem = live(prepared, bindings, after);
      return problem == null ? accepted(prepared) : rejected(problem);
    } catch (RuntimeException failure) {
      return reject(Reason.CALLBACK_FAILED, "a bound clock, sensor, state or planner callback failed");
    }
  }

  /** Rechecks preconditions now; the returned command is unscheduled and externally owned. */
  public static <T, C> Outcome<C> constructCommand(Prepared<T> prepared, Bindings<T> bindings,
                                                   CommandFactory<T, C> factory) {
    Rejection missing = missing(bindings);
    if (missing != null) return rejected(missing);
    if (factory == null) return reject(Reason.MISSING_BINDING, "commandFactory is required");
    if (prepared == null) return reject(Reason.INVALID_REQUEST, "an accepted preparation is required");
    try {
      long now = bindings.robotClockNs().getAsLong();
      Rejection problem = live(prepared, bindings, now);
      if (problem != null) return rejected(problem);
      C command = factory.construct(prepared, bindings);
      if (command == null) return reject(Reason.FACTORY_RETURNED_NULL, "commandFactory returned no command");
      long after = bindings.robotClockNs().getAsLong();
      if (after < now) return reject(Reason.CLOCK_INVALID, "robot clock regressed during construction");
      problem = live(prepared, bindings, after);
      if (problem != null) return rejected(problem);
      return accepted(command);
    } catch (RuntimeException failure) {
      return reject(Reason.CALLBACK_FAILED, "a bound callback or command factory failed");
    }
  }

  /**
   * May be called by the existing command's lifecycle immediately before starting this plan.
   * Includes start-pose drift checks, so this is not a running-plan validity check after movement.
   * This only samples and validates; it neither controls nor stops the robot. The caller remains
   * responsible for authoritative selection updates and trajectory validity while executing.
   */
  public static <T> Optional<Rejection> validateBeforeStart(Prepared<T> prepared, Bindings<T> bindings) {
    Rejection missing = missing(bindings);
    if (missing != null) return Optional.of(missing);
    if (prepared == null) return Optional.of(new Rejection(Reason.INVALID_REQUEST, "an accepted preparation is required"));
    try { return Optional.ofNullable(live(prepared, bindings, bindings.robotClockNs().getAsLong())); }
    catch (RuntimeException failure) { return Optional.of(new Rejection(Reason.CALLBACK_FAILED, "a bound callback failed")); }
  }

  private static Rejection missing(Bindings<?> bindings) {
    if (bindings == null) return new Rejection(Reason.MISSING_BINDING, "bindings are required");
    if (bindings.stateProvider() == null) return new Rejection(Reason.MISSING_BINDING, "stateProvider is required");
    if (bindings.planner() == null) return new Rejection(Reason.MISSING_BINDING, "trajectoryPlanner is required");
    if (bindings.follower() == null) return new Rejection(Reason.MISSING_BINDING, "followerPort is required");
    if (bindings.drive() == null) return new Rejection(Reason.MISSING_BINDING, "drivePort is required");
    if (bindings.robotClockNs() == null) return new Rejection(Reason.MISSING_BINDING, "robotClockNs is required");
    if (bindings.cancelled() == null) return new Rejection(Reason.MISSING_BINDING, "cancellation sensor is required");
    if (bindings.possessionPresent() == null) return new Rejection(Reason.MISSING_BINDING, "possession sensor is required");
    return null;
  }

  private static Rejection cancelled(GoalRequest request, Bindings<?> bindings) {
    if (bindings.cancelled().getAsBoolean()) return new Rejection(Reason.CANCELLED, "caller cancellation is active");
    if (request.kind() != GoalKind.POSE && bindings.possessionPresent().getAsBoolean()) {
      return new Rejection(Reason.POSSESSION_PRESENT, "pickup goal rejected because possession is already present");
    }
    return null;
  }

  private static Rejection state(Context context, RobotState state, long now) {
    if (state == null) return new Rejection(Reason.CALLBACK_FAILED, "stateProvider returned no state");
    if (!context.frame().equals(state.frame())) return new Rejection(Reason.FRAME_MISMATCH, "pose reset, world, origin or robot epoch changed");
    if (!fresh(state.sampledRobotNs(), now, context.validationBounds().maximumStateAgeNs())) {
      return new Rejection(Reason.STATE_STALE, "measured state is future or older than the configured age");
    }
    if (!context.fieldBounds().contains(state.pose())) return new Rejection(Reason.STATE_OUT_OF_BOUNDS, "measured canonical pose is outside configured bounds");
    return null;
  }

  private static Rejection selection(GoalRequest request, long now) {
    if (request.selection().isEmpty()) return null;
    SelectionEvidence selected = request.selection().orElseThrow();
    if (!request.context().frame().equals(selected.frame())) return new Rejection(Reason.FRAME_MISMATCH, "selected goal belongs to a different reset, world, origin or epoch");
    long age = request.context().validationBounds().maximumSelectionAgeNs();
    if (!fresh(selected.selectedRobotNs(), now, age)
        || !fresh(selected.oldestMemberObservationRobotNs(), now, age)
        || !fresh(selected.worldSnapshotRobotNs(), now, age)
        || selected.oldestMemberObservationRobotNs() > selected.worldSnapshotRobotNs()
        || selected.worldSnapshotRobotNs() > selected.selectedRobotNs()) {
      return new Rejection(Reason.SELECTION_STALE, "selection, every member observation and world snapshot require fresh ordered times");
    }
    List<String> ids = selected.persistentMemberIds();
    if (ids.size() > request.context().validationBounds().maximumClusterMembers()) return new Rejection(Reason.TOO_MANY_MEMBERS, "selection exceeds configured persistent member bound");
    if (new HashSet<>(ids).size() != ids.size()) return new Rejection(Reason.DUPLICATE_MEMBER, "persistent members must be deduplicated by the owning tracker");
    if ((request.kind() == GoalKind.SELECTED_OBJECT && ids.size() != 1)
        || (request.kind() == GoalKind.CLUSTER && ids.size() < 2)) {
      return new Rejection(Reason.SELECTION_UNSAFE, "object requires one persistent member; cluster requires at least two");
    }
    if (!selected.reachable() || selected.minimumApproachClearanceMeters() < request.context().motionLimits().minimumClearanceMeters()) {
      return new Rejection(Reason.SELECTION_UNSAFE, "selector must supply reachable approach and configured footprint clearance");
    }
    return null;
  }

  private static Rejection plan(GoalRequest request, RobotState initialState, Plan<?> plan, long now) {
    PlanEvidence proof = plan.evidence();
    if (!request.requestId().equals(proof.requestId()) || !request.context().frame().equals(proof.frame())
        || !request.goal().equals(proof.plannedGoal()) || !initialState.equals(proof.plannedInitialState())
        || !request.context().motionLimits().equals(proof.appliedMotionLimits())
        || !request.context().fieldBounds().equals(proof.appliedFieldBounds())
        || proof.plannedRobotNs() < request.requestedRobotNs()) {
      return new Rejection(Reason.PLAN_MISMATCH, "planner evidence must match request, goal, frame, initial measured state, limits and bounds");
    }
    ValidationBounds bounds = request.context().validationBounds();
    if (!fresh(proof.plannedRobotNs(), now, bounds.maximumPlanAgeNs())) return new Rejection(Reason.PLAN_STALE, "planner evidence is future or older than the configured age");
    if (proof.sampleCount() < 2 || proof.sampleCount() > bounds.maximumPlanSamples()
        || proof.durationSeconds() > bounds.maximumPathDurationSeconds()
        || proof.pathLengthMeters() > bounds.maximumPathLengthMeters()) {
      return new Rejection(Reason.PLAN_TOO_LARGE, "trajectory evidence exceeds configured sample, duration or path length bounds");
    }
    if (!proof.reachable() || !proof.staysWithinFieldBounds() || !proof.satisfiesMotionLimits()
        || proof.minimumClearanceMeters() < request.context().motionLimits().minimumClearanceMeters()) {
      return new Rejection(Reason.PLAN_UNSAFE, "real planner/validator must attest reachability, field bounds, motion limits and footprint clearance");
    }
    return null;
  }

  private static Rejection live(Prepared<?> prepared, Bindings<?> bindings, long now) {
    GoalRequest request = prepared.request();
    if (now < request.requestedRobotNs()) return new Rejection(Reason.CLOCK_INVALID, "robot monotonic clock regressed");
    // Callbacks can consume time even when they return immutable data sampled at entry. Obtain
    // the final state/sensor results first, then validate all their ages at actual completion.
    // Nothing after this resample invokes external callbacks, so an expired callback result
    // cannot become accepted merely by having been fresh when the callback started.
    RobotState current = bindings.stateProvider().snapshot(now);
    Rejection problem = cancelled(request, bindings);
    long finished = bindings.robotClockNs().getAsLong();
    if (finished < now) return new Rejection(Reason.CLOCK_INVALID, "robot monotonic clock regressed during live callbacks");
    if (problem == null) problem = selection(request, finished);
    if (problem == null) problem = plan(request, prepared.planningState(), prepared.plan(), finished);
    if (problem == null) problem = state(request.context(), current, finished);
    if (problem != null) return problem;
    ValidationBounds bounds = request.context().validationBounds();
    RobotState initial = prepared.planningState();
    if (Math.hypot(current.pose().xMeters() - initial.pose().xMeters(), current.pose().yMeters() - initial.pose().yMeters())
            > bounds.maximumStartPositionDriftMeters()
        || Math.abs(angleDifference(current.pose().yawRadians(), initial.pose().yawRadians())) > bounds.maximumStartHeadingDriftRadians()
        || Math.hypot(current.measuredSpeeds().forwardMetersPerSecond() - initial.measuredSpeeds().forwardMetersPerSecond(),
            current.measuredSpeeds().leftMetersPerSecond() - initial.measuredSpeeds().leftMetersPerSecond())
            > bounds.maximumStartLinearSpeedDriftMetersPerSecond()
        || Math.abs(current.measuredSpeeds().counterclockwiseRadiansPerSecond()
            - initial.measuredSpeeds().counterclockwiseRadiansPerSecond()) > bounds.maximumStartAngularSpeedDriftRadiansPerSecond()) {
      return new Rejection(Reason.PLAN_START_CHANGED, "measured start pose or speeds drifted beyond caller-configured tolerances");
    }
    return null;
  }

  private static double angleDifference(double a, double b) {
    // Reduce independently so two finite extreme angles cannot overflow during subtraction.
    double delta = Math.IEEEremainder(a, Math.PI * 2) - Math.IEEEremainder(b, Math.PI * 2);
    return Math.IEEEremainder(delta, Math.PI * 2);
  }
  private static boolean fresh(long sampled, long now, long maxAge) { return sampled <= now && now - sampled <= maxAge; }
  private static <T> Outcome<T> accepted(T value) { return new Outcome<>(Optional.of(value), Optional.empty()); }
  private static <T> Outcome<T> rejected(Rejection rejection) { return new Outcome<>(Optional.empty(), Optional.of(rejection)); }
  private static <T> Outcome<T> reject(Reason reason, String detail) { return rejected(new Rejection(reason, detail)); }
  private static void text(String value, String name, int max) {
    if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException("invalid " + name);
  }
  private static void finite(double value, String name) { if (!Double.isFinite(value)) throw new IllegalArgumentException("nonfinite " + name); }
  private static void positive(double value, String name) { finite(value, name); if (value <= 0) throw new IllegalArgumentException("nonpositive " + name); }
  private static void nonnegativeFinite(double value, String name) { finite(value, name); if (value < 0) throw new IllegalArgumentException("negative " + name); }
  private static void nonnegative(long value, String name) { if (value < 0) throw new IllegalArgumentException("negative " + name); }
  private static void positive(long value, String name) { if (value <= 0) throw new IllegalArgumentException("nonpositive " + name); }
}
