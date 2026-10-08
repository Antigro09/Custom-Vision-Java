package org.customvision.controls;

import org.customvision.controls.DriveRequests.Bindings;
import org.customvision.controls.DriveRequests.CommandFactory;
import org.customvision.controls.DriveRequests.Context;
import org.customvision.controls.DriveRequests.FieldBounds;
import org.customvision.controls.DriveRequests.FrameIdentity;
import org.customvision.controls.DriveRequests.MotionLimits;
import org.customvision.controls.DriveRequests.Outcome;
import org.customvision.controls.DriveRequests.Plan;
import org.customvision.controls.DriveRequests.PlanEvidence;
import org.customvision.controls.DriveRequests.PlanningResult;
import org.customvision.controls.DriveRequests.Pose2;
import org.customvision.controls.DriveRequests.Prepared;
import org.customvision.controls.DriveRequests.RobotRelativeSpeeds;
import org.customvision.controls.DriveRequests.RobotState;
import org.customvision.controls.DriveRequests.ValidationBounds;

/**
 * Compile-tested boundary example; there is no scheduler, execution method, or physical binding.
 * {@link #run} uses synthetic CPU-only evidence and does not qualify a drivetrain or trajectory.
 */
public final class NoMotionExample {
  private NoMotionExample() {}

  /**
   * Reusable shape for the team's existing scheduler/command factory. All configuration and
   * planner/follower/drive/sensor bindings come from the application. The caller must inspect
   * rejection and subsequently schedule/own the command. The factory must not actuate on construct.
   */
  public static <T, C> Outcome<C> constructPoseCommand(String requestId, Pose2 goal, Context context,
      Bindings<T> bindings, CommandFactory<T, C> teamFactory) {
    Outcome<Prepared<T>> result = DriveRequests.prepareDriveToPose(requestId, goal, context, bindings);
    if (!result.accepted()) return new Outcome<>(java.util.Optional.empty(), result.rejection());
    return DriveRequests.constructCommand(result.orElseThrow(), bindings, teamFactory);
  }

  /** An opaque synthetic trajectory handle, not a real collision-checked path. */
  private record SyntheticTrajectory(String name) {}
  /** An unscheduled example object, intentionally having no execute/actuator implementation. */
  private record UnscheduledExample(Prepared<SyntheticTrajectory> preparation,
                                    Bindings<SyntheticTrajectory> liveBindings) {}

  public static void main(String[] args) {
    run();
    System.out.println("NoMotionExample constructed an unscheduled synthetic request; zero drive/follower calls");
  }

  public static void run() {
    final long now = 1_000_000_000L;
    FrameIdentity frame = new FrameIdentity("synthetic-epoch", "synthetic-field-origin-nwu", "synthetic-reset", "synthetic-world");
    // Synthetic test values only. A real binding must supply measured bounds and constraints.
    Context context = new Context(frame, new FieldBounds(0, 10, 0, 10), new MotionLimits(1, 1, 1, 1, 0.5),
        new ValidationBounds(10_000_000L, 20_000_000L, 10_000_000L, 5, 5, 16, 4, 0, 0, 0, 0));
    int[] calls = {0};
    DriveRequests.DrivePort noHardware = new DriveRequests.DrivePort() {
      @Override public void apply(RobotRelativeSpeeds speeds, MotionLimits limits) {
        calls[0]++; throw new AssertionError("example attempted to drive");
      }
      @Override public void stop() { calls[0]++; throw new AssertionError("example attempted an actuator call"); }
    };
    Bindings<SyntheticTrajectory> bindings = new Bindings<>(
        time -> new RobotState(new Pose2(1, 1, 0), RobotRelativeSpeeds.stopped(), frame, time),
        (request, state) -> PlanningResult.planned(new Plan<>(new SyntheticTrajectory("cpu-contract-test"),
            new PlanEvidence(request.requestId(), frame, request.goal(), state, context.motionLimits(), context.fieldBounds(), now,
                2, 1, 1, 0.5, true, true, true))),
        (trajectory, state, time) -> { calls[0]++; throw new AssertionError("example attempted to run a follower"); },
        noHardware, () -> now, () -> false, () -> false);
    UnscheduledExample command = constructPoseCommand("no-motion-example", new Pose2(2, 1, 0), context,
        bindings, UnscheduledExample::new).orElseThrow();
    if (command.liveBindings() != bindings || command.preparation().request().goal().xMeters() != 2 || calls[0] != 0) {
      throw new AssertionError("no-motion construction invariant");
    }
  }
}
