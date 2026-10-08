# Separate hardware checks — unrun

These checks require a separately authorized hardware/integration session. Desktop
unit, fixture and loopback checks do not establish their outcomes.

1. Record exact controller hardware/image, WPILib, JDK, Driver Station and every
   vendor version. For 2026 use roboRIO; for alpha-7 use its matched Systemcore
   image/DS/vendor set. There is no official 2026/Systemcore profile here.
2. With motion disabled, verify the NT server clock maps to the configured robot
   estimator monotonic epoch; measure offsets/uncertainty and confirm alpha-7
   metadata nanoseconds versus JSON microseconds. Verify capture correction for
   the actual camera mode/exposure, including a possibly measured zero correction.
3. Exercise one Jetson power loss while another NT peer stays connected, retention,
   reconnection, revisions/new boots, watchdog invalidation, queue saturation and
   source recovery. Observe action clearing, bounded queue/decoder load and age
   counters at each robot cycle. Record hardware evidence and thresholds.
4. Verify measured mounting, fixed field origin, optical/AprilTag/POI/object signs,
   historical coverage/resets and cross-camera ordering. Calibrate robot fusion
   uncertainty from field testing; preserve joint/per-tag correlations.
5. Obtain the separate robot-code integration/deployment scope before inserting
   estimators, adding commands or actuating. Prompt 6 remains on hold.
