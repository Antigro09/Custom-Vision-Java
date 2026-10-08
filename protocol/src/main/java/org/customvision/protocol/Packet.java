package org.customvision.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

/**
 * Immutable coherent result, retaining raw capture-relative geometry and timestamp provenance.
 * Only ProtocolDecoder-produced packets are contract-validated. The public constructor supports
 * fake-clock/replay inputs and deep immutability; it does not replace decoder validation.
 */
public record Packet(SourceKey source, long schemaVersion, String bootId, long frameId,
                     OptionalLong packetSeq, String revision, boolean connected,
                     long captureServerUs, boolean timeSyncValid, Map<String, Object> fields) {
    public Packet {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(bootId, "bootId");
        Objects.requireNonNull(packetSeq, "packetSeq");
        Objects.requireNonNull(revision, "revision");
        fields = immutableMap(Objects.requireNonNull(fields, "fields"));
    }

    public record ObservationId(SourceKey source, String bootId, long frameId) {}
    public record TrackId(SourceKey source, String bootId, long localTrackId) {}
    public record Vec3(double x, double y, double z) {}
    public record QuaternionWxyz(double w, double x, double y, double z) {}
    public record Pose(Vec3 translation, QuaternionWxyz rotation, String frame) {}

    public record TimestampProvenance(OptionalLong captureServerUs, OptionalLong captureMonotonicUs,
                                      OptionalLong publishUnixUs, Optional<String> source,
                                      boolean timeSyncValid, OptionalDouble captureLatencyOffsetMs,
                                      Optional<Boolean> captureCorrectionVerified,
                                      OptionalDouble captureCorrectionUncertaintyMs) {}
    public record Localization(boolean valid, Optional<Pose> fieldCamera, Optional<Pose> fieldRobot,
                               List<Long> usedTagIds, Map<String, Object> quality,
                               Map<String, Object> raw) {
        public Localization {
            usedTagIds = List.copyOf(usedTagIds);
            quality = immutableMap(quality);
            raw = immutableMap(raw);
        }
    }
    public record ObjectTarget(TrackId identity, Vec3 translation, String frame,
                               long captureMonotonicUs, List<List<Double>> covarianceXyM2,
                               Map<String, Object> uncertainty, Map<String, Object> raw) {
        public ObjectTarget {
            covarianceXyM2 = covarianceXyM2.stream().map(List::copyOf).toList();
            uncertainty = immutableMap(uncertainty);
            raw = immutableMap(raw);
        }
    }
    public record ObjectObservations(boolean valid, List<ObjectTarget> targets,
                                     Optional<TrackId> selectedTrackId,
                                     Map<String, Object> raw) {
        public ObjectObservations {
            targets = List.copyOf(targets);
            raw = immutableMap(raw);
        }
    }
    /** Optical vectors retain OpenCV right/down/forward axes; robot vectors retain NWU. */
    public record PoiTarget(String name, long tagId, boolean valid, Optional<Vec3> cameraOptical,
                            Optional<Vec3> robotNwu, Map<String, Object> quality,
                            Map<String, Object> raw) {
        public PoiTarget {
            quality = immutableMap(quality);
            raw = immutableMap(raw);
        }
    }
    public record PoiObservations(boolean valid, Optional<String> selectedName,
                                  List<PoiTarget> targets, Map<String, Object> raw) {
        public PoiObservations {
            targets = List.copyOf(targets);
            raw = immutableMap(raw);
        }
    }

    /** Roles distinguish field-camera, field-robot and tag-relative transforms. */
    public record PoseCandidate(String location, Pose pose, List<Long> tagIds,
                                String provenance, Map<String, Object> quality) {
        public PoseCandidate {
            tagIds = List.copyOf(tagIds);
            quality = immutableMap(quality);
        }
    }

    public ObservationId observationId() { return new ObservationId(source, bootId, frameId); }
    public TrackId trackId(long localId) { return new TrackId(source, bootId, localId); }
    public String profile() { return fields.get("protocol_profile") instanceof String p ? p : "legacy-schema2"; }

    public TimestampProvenance timestamps() {
        Map<String, Object> timing = family("timing").orElse(Map.of());
        return new TimestampProvenance(optionalLong(fields.get("capture_server_us")),
                optionalLong(fields.get("capture_monotonic_us")), optionalLong(fields.get("publish_unix_us")),
                optionalString(fields.get("timestamp_source")), timeSyncValid,
                optionalDouble(fields.get("capture_latency_offset_ms")),
                timing.get("capture_correction_verified") instanceof Boolean b ? Optional.of(b) : Optional.empty(),
                optionalDouble(timing.get("capture_correction_uncertainty_ms")));
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> family(String name) {
        Object value = fields.get(name);
        return value instanceof Map<?, ?> ? Optional.of((Map<String, Object>) value) : Optional.empty();
    }

    public boolean familyValid(String name) {
        return connected && family(name).map(value -> Boolean.TRUE.equals(value.get("valid"))).orElse(false);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> detections() {
        Object value = fields.get("detections");
        return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of();
    }

    /** Packet-level availability; receiver lifecycle/clock policy must still approve freshness. */
    public boolean usable() {
        return connected && (!detections().isEmpty() || familyValid("localization")
                || familyValid("poi") || familyValid("objects"));
    }

    public Optional<Pose> pose(String familyName, String field) {
        if (!familyName.equals("localization") || !List.of("field_to_robot", "field_to_camera").contains(field)) return Optional.empty();
        if (!familyValid(familyName)) return Optional.empty();
        return family(familyName).map(family -> family.get(field)).filter(Map.class::isInstance)
                .map(value -> poseOf(castMap(value)));
    }

    public Optional<Localization> localization() {
        return family("localization").map(value -> new Localization(familyValid("localization"),
                pose("localization", "field_to_camera"), pose("localization", "field_to_robot"),
                longs(value.get("used_tag_ids")), quality(value), value));
    }

    public Optional<ObjectObservations> objects() {
        return family("objects").map(value -> {
            boolean valid = familyValid("objects");
            List<ObjectTarget> targets = valid ? maps(value.get("targets")).stream().map(this::objectTarget).toList() : List.of();
            Optional<TrackId> selected = valid && value.get("selected_track_id") instanceof Long id ? Optional.of(trackId(id)) : Optional.empty();
            return new ObjectObservations(valid, targets, selected, value);
        });
    }

    public List<ObjectTarget> objectTargets() { return objects().map(ObjectObservations::targets).orElse(List.of()); }
    public Optional<ObjectTarget> selectedTarget() {
        return objects().flatMap(value -> value.selectedTrackId().flatMap(selected -> value.targets().stream()
                .filter(target -> target.identity().equals(selected)).findFirst()));
    }

    public Optional<PoiObservations> poi() {
        return family("poi").map(value -> {
            boolean familyValid = familyValid("poi");
            List<PoiTarget> targets = maps(value.get("targets")).stream().map(target -> {
                boolean valid = familyValid && Boolean.TRUE.equals(target.get("valid"));
                return new PoiTarget((String) target.get("name"), (Long) target.get("tag_id"), valid,
                        valid ? optionalVec3(target.get("camera_translation_m")) : Optional.empty(),
                        valid ? optionalVec3(target.get("robot_translation_m")) : Optional.empty(),
                        select(target, List.of("geometry_valid", "calibration_verified", "invalid_reason", "tx_deg",
                                "ty_deg", "robot_yaw_deg", "robot_elevation_deg", "distance_m", "camera_frame",
                                "offset_frame", "offset_m")), target);
            }).toList();
            return new PoiObservations(familyValid, familyValid ? optionalString(value.get("selected_name")) : Optional.empty(), targets, value);
        });
    }
    public List<PoiTarget> poiTargets() { return poi().map(value -> value.targets().stream().filter(PoiTarget::valid).toList()).orElse(List.of()); }
    public Optional<PoiTarget> selectedPoi() {
        return poi().flatMap(value -> value.selectedName().flatMap(selected -> value.targets().stream()
                .filter(target -> target.valid() && target.name().equals(selected)).findFirst()));
    }

    /** Complete bounded device/timing diagnostics, with no implied estimator covariance. */
    public Map<String, Object> diagnostics() {
        return immutableMap(select(fields, List.of("backend", "detector_device", "pose_device", "input_kind",
                "mode", "error", "frame_size", "processing_ms", "detector_ms", "localization_ms", "queue_ms",
                "native_timings", "inference_timings", "fps", "dropped_frames", "single_tag_pose_ms")));
    }

    private ObjectTarget objectTarget(Map<String, Object> value) {
        Map<String, Object> uncertainty = castMap(value.get("uncertainty"));
        List<?> covariance = (List<?>) uncertainty.get("covariance_xy_m2");
        List<List<Double>> matrix = covariance.stream().map(row -> ((List<?>) row).stream()
                .map(entry -> ((Number) entry).doubleValue()).toList()).toList();
        return new ObjectTarget(trackId((Long) value.get("track_id")), vec3((List<?>) value.get("translation_m")),
                (String) value.get("frame"), (Long) value.get("capture_monotonic_us"), matrix, uncertainty, value);
    }

    public List<PoseCandidate> poseCandidates() {
        List<PoseCandidate> output = new ArrayList<>();
        if (!connected) return List.of();
        family("localization").filter(value -> Boolean.TRUE.equals(value.get("valid"))).ifPresent(value -> {
            List<Long> tags = longs(value.get("used_tag_ids"));
            String method = string(value.get("method"), "unspecified");
            for (String field : List.of("field_to_robot", "field_to_camera")) {
                if (value.get(field) instanceof Map<?, ?> pose) {
                    output.add(new PoseCandidate("localization." + field, poseOf(castMap(pose)), tags,
                            method, quality(value)));
                }
            }
        });
        int index = 0;
        for (Map<String, Object> detection : source.type().equals("apriltag") ? detections() : List.<Map<String, Object>>of()) {
            if (Boolean.TRUE.equals(detection.get("pose_valid"))) {
                for (String field : List.of("camera_to_target", "robot_to_target")) {
                    if (detection.get(field) instanceof Map<?, ?> pose) {
                        output.add(new PoseCandidate("detections[" + index + "]." + field,
                                poseOf(castMap(pose)), List.of((Long) detection.get("id")),
                                string(detection.get("pose_source"), "unspecified"), quality(detection)));
                    }
                }
            }
            index++;
        }
        return List.copyOf(output);
    }

    /** Diagnostic residuals remain named pixel errors, never estimator standard deviations. */
    private static Map<String, Object> quality(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("method", "reprojection_error_px", "pose_ambiguity", "ambiguity",
                "decision_margin", "hamming", "distance_m", "tag_reprojection_errors_px",
                "inlier_tag_count", "rejected_tag_ids", "pose_source", "pose_device")) {
            if (value.containsKey(key)) result.put(key, value.get(key));
        }
        return result;
    }

    private static Pose poseOf(Map<String, Object> value) {
        List<?> t = (List<?>) value.get("translation_m");
        List<?> q = (List<?>) value.get("rotation_quaternion_wxyz");
        return new Pose(new Vec3(number(t, 0), number(t, 1), number(t, 2)),
                new QuaternionWxyz(number(q, 0), number(q, 1), number(q, 2), number(q, 3)),
                (String) value.get("frame"));
    }
    private static double number(List<?> values, int index) { return ((Number) values.get(index)).doubleValue(); }
    private static Vec3 vec3(List<?> values) { return new Vec3(number(values, 0), number(values, 1), number(values, 2)); }
    private static Optional<Vec3> optionalVec3(Object value) { return value instanceof List<?> list ? Optional.of(vec3(list)) : Optional.empty(); }
    private static OptionalLong optionalLong(Object value) { return value instanceof Long number ? OptionalLong.of(number) : OptionalLong.empty(); }
    private static OptionalDouble optionalDouble(Object value) { return value instanceof Number number ? OptionalDouble.of(number.doubleValue()) : OptionalDouble.empty(); }
    private static Optional<String> optionalString(Object value) { return value instanceof String text ? Optional.of(text) : Optional.empty(); }
    private static String string(Object value, String fallback) { return value instanceof String s ? s : fallback; }
    @SuppressWarnings("unchecked") private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Long> longs(Object value) { return value instanceof List<?> ? (List<Long>) value : List.of(); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> maps(Object value) { return value instanceof List<?> ? (List<Map<String, Object>>) value : List.of(); }
    private static Map<String, Object> select(Map<String, Object> value, List<String> keys) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : keys) if (value.containsKey(key)) result.put(key, value.get(key));
        return result;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(Objects.requireNonNull(key), immutable(value)));
        return Collections.unmodifiableMap(result);
    }
    private static Object immutable(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) throw new IllegalArgumentException("JSON object keys must be strings");
                result.put(text, immutable(item));
            });
            return Collections.unmodifiableMap(result);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(immutable(item));
            return Collections.unmodifiableList(result);
        }
        if (value == null || value instanceof String || value instanceof Long || value instanceof Double
                || value instanceof Boolean) return value;
        throw new IllegalArgumentException("unsupported raw JSON value type");
    }
}
