package org.customvision.protocol;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

import static org.customvision.protocol.DecodeException.Reason.*;

/** Decoder for the pinned schema-2 producer and its named additive profile. No HAL/NT dependencies. */
public final class ProtocolDecoder {
    public static final String ADDITIVE_PROFILE = "custom-vision-schema2-2026.1";
    public static final double QUATERNION_NORM_TOLERANCE = 1e-5;
    private static final String NWU = "wpilib_nwu";
    private static final String ROBOT_CAPTURE_NWU = "robot_relative_at_capture_wpilib_nwu";
    private static final long MAX_EXACT_SEQUENCE = 9_007_199_254_740_991L;

    /** Bounds apply after NTCore has allocated a String. They cannot bound that earlier allocation. */
    public record Limits(int maxPayloadChars, int maxStringChars, int maxTokens, int maxDepth,
                         int maxArrayEntries, int maxObjectFields, int maxDetections, int maxTargets) {
        public Limits {
            if (maxPayloadChars < 1 || maxStringChars < 1 || maxTokens < 1 || maxDepth < 1
                    || maxDepth > 128 || maxArrayEntries < 1 || maxObjectFields < 1
                    || maxDetections < 1 || maxTargets < 1) {
                throw new IllegalArgumentException("limits must be positive; nesting is capped at 128");
            }
        }
        public static Limits defaults() { return new Limits(262_144, 4_096, 32_768, 24, 4_096, 256, 256, 256); }
    }

    private final Limits limits;
    public ProtocolDecoder() { this(Limits.defaults()); }
    public ProtocolDecoder(Limits limits) { this.limits = Objects.requireNonNull(limits); }
    public Limits limits() { return limits; }

    public Packet decode(SourceKey source, String payload) throws DecodeException {
        Objects.requireNonNull(source, "source");
        Map<String, Object> root = object(Json.parse(payload, limits), "$");
        long schema = integer(required(root, "schema_version", "$"), "$.schema_version", 0, Long.MAX_VALUE);
        if (schema != 2) reject(UNSUPPORTED_VERSION, "$.schema_version", "supported major is 2");
        String boot = text(required(root, "boot_id", "$"), "$.boot_id", true);
        String pipeline = text(required(root, "pipeline", "$"), "$.pipeline", true);
        String type = text(required(root, "type", "$"), "$.type", true);
        if (!pipeline.equals(source.pipeline()) || !type.equals(source.type())) {
            reject(SOURCE_MISMATCH, "$", "payload pipeline/type differs from configured source");
        }
        String mode = text(required(root, "mode", "$"), "$.mode", true);
        if (!(type.equals("apriltag") ? Set.of("2d", "3d") : Set.of("detect", "segment")).contains(mode)) {
            reject(OUT_OF_RANGE, "$.mode", "mode is incompatible with pipeline type");
        }
        text(required(root, "backend", "$"), "$.backend", true);
        enumText(required(root, "detector_device", "$"), "$.detector_device", Set.of("cpu", "cuda"));
        enumText(required(root, "pose_device", "$"), "$.pose_device", Set.of("cpu", "cuda", "mixed", "none"));
        text(required(root, "input_kind", "$"), "$.input_kind", true);
        boolean connected = bool(required(root, "connected", "$"), "$.connected");
        long frame = integer(required(root, "frame_id", "$"), "$.frame_id", 0, Long.MAX_VALUE);
        long captureMono = integer(required(root, "capture_monotonic_us", "$"), "$.capture_monotonic_us", 0, Long.MAX_VALUE);
        integer(required(root, "publish_unix_us", "$"), "$.publish_unix_us", 0, Long.MAX_VALUE);
        number(required(root, "latency_ms", "$"), "$.latency_ms", 0, Double.MAX_VALUE);
        number(required(root, "capture_latency_offset_ms", "$"), "$.capture_latency_offset_ms");
        constant(required(root, "timestamp_source", "$"), "$.timestamp_source", "host_frame_read_complete", OUT_OF_RANGE);
        boolean synchronizedTime = bool(required(root, "time_sync_valid", "$"), "$.time_sync_valid");
        Object captureRaw = required(root, "capture_server_us", "$" );
        long captureServer = captureRaw == null ? 0 : integer(captureRaw, "$.capture_server_us", 0, Long.MAX_VALUE);
        if (synchronizedTime && captureRaw == null || !synchronizedTime && captureRaw != null) {
            reject(OUT_OF_RANGE, "$.capture_server_us", "synchronized captures require integer micros; unavailable sync requires null");
        }
        // Deployed failures may omit diagnostics/error. Absence never fabricates an error or measurement.
        optionalText(root, "error", "$", true);
        if (connected && root.get("error") != null) reject(OUT_OF_RANGE, "$.error", "connected packet has an error");
        List<Object> detections = array(required(root, "detections", "$"), "$.detections", limits.maxDetections());
        if (!connected && !detections.isEmpty()) reject(OUT_OF_RANGE, "$.detections", "disconnected packet contains detections");
        Set<Long> tagIds = new HashSet<>();
        for (int i = 0; i < detections.size(); i++) {
            String path = "$.detections[" + i + "]";
            Map<String, Object> detection = object(detections.get(i), path);
            if (type.equals("apriltag")) validateTag(detection, path, tagIds);
            else validateDetection(detection, path);
        }
        if (root.containsKey("localization")) validateLocalization(object(root.get("localization"), "$.localization"), tagIds);
        if (root.containsKey("objects")) validateObjects(object(root.get("objects"), "$.objects"), detections, captureMono);
        else validateUnresolvedReferences(detections);
        if (root.containsKey("poi")) validatePoi(object(root.get("poi"), "$.poi"), captureMono, tagIds);
        validateDiagnostics(root);

        OptionalLong sequence = OptionalLong.empty();
        String revision = "legacy";
        if (root.containsKey("protocol_profile")) {
            constant(root.get("protocol_profile"), "$.protocol_profile", ADDITIVE_PROFILE, UNSUPPORTED_VERSION);
            sequence = OptionalLong.of(integer(required(root, "packet_seq", "$"), "$.packet_seq", 0, MAX_EXACT_SEQUENCE));
            StringBuilder identity = new StringBuilder();
            for (String key : List.of("calibration_revision", "mount_revision", "field_layout_revision")) {
                Object value = required(root, key, "$" );
                if (value != null) {
                    String hash = text(value, "$." + key, true);
                    if (!hash.matches("sha256:[0-9a-f]{64}")) reject(OUT_OF_RANGE, "$." + key, "revision must be opaque sha256 lowercase hex or null");
                }
                if (identity.length() != 0) identity.append('|');
                identity.append(value == null ? "null" : value);
            }
            revision = identity.toString();
        }
        if (root.containsKey("timing")) validateTiming(object(root.get("timing"), "$.timing"));
        return new Packet(source, schema, boot, frame, sequence, revision, connected, captureServer, synchronizedTime, root);
    }

    private void validateTag(Map<String, Object> detection, String path, Set<Long> ids) throws DecodeException {
        long id = integer(required(detection, "id", path), path + ".id", 0, Long.MAX_VALUE);
        ids.add(id);
        vector(required(detection, "center", path), path + ".center", 2);
        matrix(required(detection, "corners", path), path + ".corners", 4, 2);
        boolean valid = bool(required(detection, "pose_valid", path), path + ".pose_valid");
        optionalInteger(detection, "hamming", path, 0, Long.MAX_VALUE);
        optionalNumber(detection, "decision_margin", path, 0, Double.MAX_VALUE, false);
        optionalNumber(detection, "distance_m", path, 0, Double.MAX_VALUE, false);
        optionalNumber(detection, "pose_ambiguity", path, 0, 1, false);
        optionalNumber(detection, "reprojection_error_px", path, 0, Double.MAX_VALUE, false);
        optionalNumber(detection, "alternate_reprojection_error_px", path, 0, Double.MAX_VALUE, false);
        optionalNumber(detection, "yaw_deg", path, -Double.MAX_VALUE, Double.MAX_VALUE, false);
        optionalNumber(detection, "pitch_deg", path, -Double.MAX_VALUE, Double.MAX_VALUE, false);
        optionalNumber(detection, "area_pct", path, 0, Double.MAX_VALUE, false);
        optionalBool(detection, "pose_attempted", path);
        optionalBool(detection, "pose_ambiguous", path);
        if (detection.containsKey("pose_device")) enumText(detection.get("pose_device"), path + ".pose_device", Set.of("none", "cpu", "cuda"));
        for (String key : List.of("pose_source", "angle_source", "pose_invalid_reason", "localization_excluded_reason")) optionalText(detection, key, path, false);
        for (String key : List.of("rvec_rad", "tvec_m", "alternate_rvec_rad", "alternate_tvec_m")) optionalVector(detection, key, path, 3, false);
        for (String key : List.of("camera_to_target", "robot_to_target")) {
            if (detection.get(key) != null) {
                validatePose(object(detection.get(key), path + "." + key), path + "." + key);
                if (!valid) reject(OUT_OF_RANGE, path + "." + key, "invalid pose cannot contain a usable transform");
            }
        }
    }

    private void validateDetection(Map<String, Object> detection, String path) throws DecodeException {
        double[] box = vector(required(detection, "bbox_xyxy", path), path + ".bbox_xyxy", 4);
        if (!(box[0] < box[2] && box[1] < box[3])) reject(OUT_OF_RANGE, path + ".bbox_xyxy", "bounding box has nonpositive extent");
        optionalInteger(detection, "class_id", path, 0, Long.MAX_VALUE);
        optionalText(detection, "label", path, false);
        optionalText(detection, "confidence_kind", path, false);
        optionalNumber(detection, "confidence", path, -Double.MAX_VALUE, Double.MAX_VALUE, false);
        optionalVector(detection, "center", path, 2, false);
        for (String key : List.of("yaw_deg", "pitch_deg")) optionalNumber(detection, key, path, -Double.MAX_VALUE, Double.MAX_VALUE, false);
        for (String key : List.of("area_pct", "area_fraction")) optionalNumber(detection, key, path, 0, Double.MAX_VALUE, false);
        if (detection.containsKey("robot_relative")) {
            Map<String, Object> reference = object(detection.get("robot_relative"), path + ".robot_relative");
            if (bool(required(reference, "valid", path + ".robot_relative"), path + ".robot_relative.valid")) {
                integer(required(reference, "track_id", path + ".robot_relative"), path + ".robot_relative.track_id", 1, Long.MAX_VALUE);
                for (String expanded : List.of("translation_m", "frame", "range_xy_m", "bearing_deg", "uncertainty",
                        "approach", "range_from_intake_m", "anchor", "anchor_px", "method", "target_height_m",
                        "detection_index", "class_id", "label", "observed", "predicted", "capture_monotonic_us",
                        "track_observations", "track_age_ms", "selected_target")) {
                    if (reference.containsKey(expanded)) reject(BROKEN_REFERENCE, path + ".robot_relative", "valid wire reference must not duplicate canonical target geometry");
                }
            } else text(required(reference, "invalid_reason", path + ".robot_relative"), path + ".robot_relative.invalid_reason", false);
        }
        if (detection.get("segmentation") != null) {
            Map<String, Object> segmentation = object(detection.get("segmentation"), path + ".segmentation");
            optionalVector(segmentation, "centroid_px", path + ".segmentation", 2, false);
            optionalVector(segmentation, "bottom_px", path + ".segmentation", 2, false);
            if (segmentation.containsKey("contour_px")) {
                List<Object> contour = array(segmentation.get("contour_px"), path + ".segmentation.contour_px", Math.min(64, limits.maxArrayEntries()));
                for (int i = 0; i < contour.size(); i++) vector(contour.get(i), path + ".segmentation.contour_px[" + i + "]", 2);
            }
            optionalNumber(segmentation, "area_px", path + ".segmentation", 0, Double.MAX_VALUE, false);
            optionalBool(segmentation, "approximate", path + ".segmentation");
            if (segmentation.containsKey("resolution")) integerVector(segmentation.get("resolution"), path + ".segmentation.resolution", 2, 1);
        }
    }

    private void validateLocalization(Map<String, Object> value, Set<Long> detectionIds) throws DecodeException {
        String path = "$.localization";
        boolean valid = bool(required(value, "valid", path), path + ".valid");
        if (valid) text(required(value, "method", path), path + ".method", false);
        else optionalText(value, "method", path, false);
        Set<Long> used = idSet(valid ? required(value, "used_tag_ids", path) : value.getOrDefault("used_tag_ids", List.of()), path + ".used_tag_ids");
        if (!detectionIds.containsAll(used)) reject(BROKEN_REFERENCE, path + ".used_tag_ids", "used tag absent from this coherent detection list");
        long count = valid || value.containsKey("inlier_tag_count") ? integer(required(value, "inlier_tag_count", path), path + ".inlier_tag_count", 0, limits.maxDetections()) : used.size();
        if (count != used.size()) reject(BROKEN_REFERENCE, path + ".inlier_tag_count", "inlier count differs from used IDs");
        for (String key : List.of("rejected_tag_ids", "duplicate_tag_ids")) {
            if (value.containsKey(key)) {
                Set<Long> ids = idSet(value.get(key), path + "." + key);
                if (!detectionIds.containsAll(ids)) reject(BROKEN_REFERENCE, path + "." + key, "referenced tag absent from detections");
            }
        }
        for (String key : List.of("field_to_camera", "field_to_robot")) {
            Object pose = valid ? required(value, key, path) : value.get(key);
            if (pose != null) validatePose(object(pose, path + "." + key), path + "." + key);
            if (!valid && pose != null) reject(OUT_OF_RANGE, path + "." + key, "invalid localization must clear transforms");
        }
        if (valid && (value.get("field_to_camera") == null || used.isEmpty())) reject(MISSING_REQUIRED_FIELD, path, "valid localization requires field-camera pose and contributing tags");
        nullableNumber(valid ? required(value, "reprojection_error_px", path) : value.get("reprojection_error_px"), path + ".reprojection_error_px", 0, Double.MAX_VALUE);
        nullableNumber(valid ? required(value, "ambiguity", path) : value.get("ambiguity"), path + ".ambiguity", 0, 1);
        if (valid && (value.get("reprojection_error_px") == null || value.get("ambiguity") == null)) reject(MISSING_REQUIRED_FIELD, path, "valid localization lacks quality inputs");
        optionalText(value, "invalid_reason", path, true);
        optionalText(value, "robot_pose_invalid_reason", path, true);
        if (value.containsKey("pose_device")) enumText(value.get("pose_device"), path + ".pose_device", Set.of("cpu", "cuda"));
        if (value.containsKey("gpu_timings")) timingNumbers(object(value.get("gpu_timings"), path + ".gpu_timings"), path + ".gpu_timings");
        if (value.containsKey("tag_reprojection_errors_px")) {
            Map<String, Object> errors = object(value.get("tag_reprojection_errors_px"), path + ".tag_reprojection_errors_px");
            for (Map.Entry<String, Object> entry : errors.entrySet()) {
                String key = entry.getKey();
                long tag;
                try { tag = Long.parseLong(key); }
                catch (NumberFormatException e) { reject(BROKEN_REFERENCE, path + ".tag_reprojection_errors_px", "error key must name an accepted tag"); return; }
                if (!Long.toString(tag).equals(key) || !used.contains(tag)) reject(BROKEN_REFERENCE, path + ".tag_reprojection_errors_px", "error references unaccepted tag");
                number(entry.getValue(), path + ".tag_reprojection_errors_px." + key, 0, Double.MAX_VALUE);
            }
        }
        if (value.containsKey("single_tag_fallback")) {
            String fallbackPath = path + ".single_tag_fallback";
            Map<String, Object> fallback = object(value.get("single_tag_fallback"), fallbackPath);
            integer(required(fallback, "calls", fallbackPath), fallbackPath + ".calls", 0, Long.MAX_VALUE);
            number(required(fallback, "pose_ms", fallbackPath), fallbackPath + ".pose_ms", 0, Double.MAX_VALUE);
            for (Object device : array(required(fallback, "devices", fallbackPath), fallbackPath + ".devices", 2)) enumText(device, fallbackPath + ".devices", Set.of("cpu", "cuda"));
        }
    }

    private void validatePose(Map<String, Object> value, String path) throws DecodeException {
        vector(required(value, "translation_m", path), path + ".translation_m", 3);
        double[] q = vector(required(value, "rotation_quaternion_wxyz", path), path + ".rotation_quaternion_wxyz", 4);
        vector(required(value, "rotation_rpy_deg", path), path + ".rotation_rpy_deg", 3);
        constant(required(value, "frame", path), path + ".frame", NWU, ILLEGAL_FRAME);
        double norm = Math.hypot(Math.hypot(q[0], q[1]), Math.hypot(q[2], q[3]));
        // Six-decimal producer rounding can change norm by about 1e-6.
        if (!Double.isFinite(norm) || Math.abs(norm - 1.0) > QUATERNION_NORM_TOLERANCE) reject(INVALID_QUATERNION, path + ".rotation_quaternion_wxyz", "WXYZ quaternion must have unit norm within " + QUATERNION_NORM_TOLERANCE);
    }

    private void validateObjects(Map<String, Object> value, List<Object> detections, long captureMono) throws DecodeException {
        String path = "$.objects";
        boolean valid = bool(required(value, "valid", path), path + ".valid");
        if (valid || value.containsKey("frame")) constant(required(value, "frame", path), path + ".frame", ROBOT_CAPTURE_NWU, ILLEGAL_FRAME);
        constant(required(value, "motion_compensated", path), path + ".motion_compensated", false, OUT_OF_RANGE);
        if (valid || value.containsKey("capture_monotonic_us")) {
            long capture = integer(required(value, "capture_monotonic_us", path), path + ".capture_monotonic_us", 0, Long.MAX_VALUE);
            if (capture != captureMono) reject(BROKEN_REFERENCE, path + ".capture_monotonic_us", "object family timestamp differs from frame");
        }
        optionalText(value, "invalid_reason", path, true);
        if (value.containsKey("selected_target")) reject(BROKEN_REFERENCE, path + ".selected_target", "wire profile contains canonical targets and compact selection only");
        List<Object> targets = array(required(value, "targets", path), path + ".targets", limits.maxTargets());
        Object selectedRaw = required(value, "selected_track_id", path);
        Long selected = selectedRaw == null ? null : integer(selectedRaw, path + ".selected_track_id", 1, Long.MAX_VALUE);
        if (valid != !targets.isEmpty() || valid != (selected != null)) reject(BROKEN_REFERENCE, path, "valid/targets/selection disagree");
        Map<Long, Integer> references = new HashMap<>();
        Set<Integer> indices = new HashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            String targetPath = path + ".targets[" + i + "]";
            Map<String, Object> target = object(targets.get(i), targetPath);
            validateObjectTarget(target, targetPath, captureMono);
            long id = integer(target.get("track_id"), targetPath + ".track_id", 1, Long.MAX_VALUE);
            long index = integer(target.get("detection_index"), targetPath + ".detection_index", 0, Long.MAX_VALUE);
            if (index >= detections.size()) reject(BROKEN_REFERENCE, targetPath + ".detection_index", "target references absent detection");
            if (references.put(id, (int) index) != null || !indices.add((int) index)) reject(BROKEN_REFERENCE, targetPath, "duplicate track ID or detection association");
            Map<String, Object> detection = object(detections.get((int) index), "$.detections[" + index + "]");
            if (!Objects.equals(target.get("class_id"), detection.get("class_id")) || !Objects.equals(target.get("label"), detection.get("label"))) reject(BROKEN_REFERENCE, targetPath, "target class identity differs from detection");
        }
        if (selected != null && !references.containsKey(selected)) reject(BROKEN_REFERENCE, path + ".selected_track_id", "selected track absent from canonical targets");
        Set<Long> resolved = new HashSet<>();
        for (int i = 0; i < detections.size(); i++) {
            Map<String, Object> detection = object(detections.get(i), "$.detections[" + i + "]");
            if (detection.get("robot_relative") instanceof Map<?, ?> raw) {
                Map<String, Object> reference = object(raw, "$.detections[" + i + "].robot_relative");
                if (Boolean.TRUE.equals(reference.get("valid"))) {
                    long track = integer(reference.get("track_id"), "$.detections[" + i + "].robot_relative.track_id", 1, Long.MAX_VALUE);
                    if (!Objects.equals(references.get(track), i) || !resolved.add(track)) reject(BROKEN_REFERENCE, "$.detections[" + i + "].robot_relative", "broken compact target reference");
                }
            }
        }
        if (!resolved.equals(references.keySet())) reject(BROKEN_REFERENCE, path + ".targets", "canonical target has no corresponding compact detection reference");
    }

    private void validateObjectTarget(Map<String, Object> target, String path, long captureMono) throws DecodeException {
        constant(required(target, "valid", path), path + ".valid", true, OUT_OF_RANGE);
        constant(required(target, "observed", path), path + ".observed", true, OUT_OF_RANGE);
        constant(required(target, "predicted", path), path + ".predicted", false, OUT_OF_RANGE);
        constant(required(target, "frame", path), path + ".frame", ROBOT_CAPTURE_NWU, ILLEGAL_FRAME);
        constant(required(target, "approximate", path), path + ".approximate", true, OUT_OF_RANGE);
        constant(required(target, "method", path), path + ".method", "calibrated_ray_target_height_plane", OUT_OF_RANGE);
        enumText(required(target, "anchor", path), path + ".anchor", Set.of("bbox_center", "bbox_bottom", "mask_centroid", "mask_bottom"));
        vector(required(target, "translation_m", path), path + ".translation_m", 3);
        vector(required(target, "anchor_px", path), path + ".anchor_px", 2);
        for (String key : List.of("range_xy_m", "range_from_intake_m", "track_age_ms")) number(required(target, key, path), path + "." + key, 0, Double.MAX_VALUE);
        for (String key : List.of("bearing_deg", "target_height_m")) number(required(target, key, path), path + "." + key);
        integer(required(target, "track_id", path), path + ".track_id", 1, Long.MAX_VALUE);
        integer(required(target, "class_id", path), path + ".class_id", 0, Long.MAX_VALUE);
        integer(required(target, "detection_index", path), path + ".detection_index", 0, Long.MAX_VALUE);
        integer(required(target, "track_observations", path), path + ".track_observations", 1, Long.MAX_VALUE);
        text(required(target, "label", path), path + ".label", false);
        if (integer(required(target, "capture_monotonic_us", path), path + ".capture_monotonic_us", 0, Long.MAX_VALUE) != captureMono) reject(BROKEN_REFERENCE, path + ".capture_monotonic_us", "target capture differs from frame");
        optionalNumber(target, "confidence", path, -Double.MAX_VALUE, Double.MAX_VALUE, false);
        optionalText(target, "confidence_kind", path, false);
        String uncertaintyPath = path + ".uncertainty";
        Map<String, Object> uncertainty = object(required(target, "uncertainty", path), uncertaintyPath);
        constant(required(uncertainty, "model", uncertaintyPath), uncertaintyPath + ".model", "first_order_configured_pixel_height_pitch_mount", OUT_OF_RANGE);
        covariance(required(uncertainty, "covariance_xy_m2", uncertaintyPath), uncertaintyPath + ".covariance_xy_m2");
        double[] std = vector(required(uncertainty, "std_xy_m", uncertaintyPath), uncertaintyPath + ".std_xy_m", 2);
        if (std[0] < 0 || std[1] < 0) reject(OUT_OF_RANGE, uncertaintyPath + ".std_xy_m", "negative standard deviation");
        for (String key : List.of("max_position_std_m", "range_std_m", "bearing_std_deg")) number(required(uncertainty, key, uncertaintyPath), uncertaintyPath + "." + key, 0, Double.MAX_VALUE);
        String approachPath = path + ".approach";
        Map<String, Object> approach = object(required(target, "approach", path), approachPath);
        vector(required(approach, "translation_m", approachPath), approachPath + ".translation_m", 3);
        number(required(approach, "rotation_yaw_deg", approachPath), approachPath + ".rotation_yaw_deg");
        constant(required(approach, "frame", approachPath), approachPath + ".frame", ROBOT_CAPTURE_NWU, ILLEGAL_FRAME);
        constant(required(approach, "heading_policy", approachPath), approachPath + ".heading_policy", "maintain_capture_heading", OUT_OF_RANGE);
        constant(required(approach, "path_validated", approachPath), approachPath + ".path_validated", false, OUT_OF_RANGE);
    }

    private void validateUnresolvedReferences(List<Object> detections) throws DecodeException {
        for (int i = 0; i < detections.size(); i++) {
            Map<String, Object> detection = object(detections.get(i), "$.detections[" + i + "]");
            if (detection.get("robot_relative") instanceof Map<?, ?> reference && Boolean.TRUE.equals(reference.get("valid"))) reject(BROKEN_REFERENCE, "$.detections[" + i + "].robot_relative", "valid reference lacks object family");
        }
    }

    private void validatePoi(Map<String, Object> poi, long captureMono, Set<Long> detectionIds) throws DecodeException {
        String path = "$.poi";
        boolean valid = bool(required(poi, "valid", path), path + ".valid");
        Object selected = required(poi, "selected_name", path);
        nullableText(selected, path + ".selected_name");
        nullableText(required(poi, "invalid_reason", path), path + ".invalid_reason");
        if (poi.containsKey("capture_monotonic_us") && integer(poi.get("capture_monotonic_us"), path + ".capture_monotonic_us", 0, Long.MAX_VALUE) != captureMono) reject(BROKEN_REFERENCE, path + ".capture_monotonic_us", "POI capture differs from frame");
        for (String key : List.of("uses_odometry", "uses_field_layout")) if (poi.containsKey(key)) constant(poi.get(key), path + "." + key, false, OUT_OF_RANGE);
        List<Object> targets = array(required(poi, "targets", path), path + ".targets", Math.min(32, limits.maxTargets()));
        Set<String> names = new HashSet<>();
        boolean selectedValid = false;
        for (int i = 0; i < targets.size(); i++) {
            String targetPath = path + ".targets[" + i + "]";
            Map<String, Object> target = object(targets.get(i), targetPath);
            String name = text(required(target, "name", targetPath), targetPath + ".name", true);
            if (!names.add(name)) reject(BROKEN_REFERENCE, targetPath + ".name", "duplicate POI name");
            long tagId = integer(required(target, "tag_id", targetPath), targetPath + ".tag_id", 0, Long.MAX_VALUE);
            vector(required(target, "offset_m", targetPath), targetPath + ".offset_m", 3);
            constant(required(target, "offset_frame", targetPath), targetPath + ".offset_frame", "tag_wpilib", ILLEGAL_FRAME);
            boolean targetValid = bool(required(target, "valid", targetPath), targetPath + ".valid");
            boolean geometryValid = bool(required(target, "geometry_valid", targetPath), targetPath + ".geometry_valid");
            boolean verified = bool(required(target, "calibration_verified", targetPath), targetPath + ".calibration_verified");
            nullableText(required(target, "invalid_reason", targetPath), targetPath + ".invalid_reason");
            nullableVector(required(target, "pixel", targetPath), targetPath + ".pixel", 2);
            nullableVector(required(target, "camera_translation_m", targetPath), targetPath + ".camera_translation_m", 3);
            nullableVector(required(target, "robot_translation_m", targetPath), targetPath + ".robot_translation_m", 3);
            for (String key : List.of("tx_deg", "ty_deg", "robot_yaw_deg", "robot_elevation_deg")) nullableNumber(required(target, key, targetPath), targetPath + "." + key, -Double.MAX_VALUE, Double.MAX_VALUE);
            optionalNumber(target, "distance_m", targetPath, 0, Double.MAX_VALUE, false);
            optionalBool(target, "in_image", targetPath);
            if (target.containsKey("camera_frame")) constant(target.get("camera_frame"), targetPath + ".camera_frame", "opencv_right_down_forward", ILLEGAL_FRAME);
            if (geometryValid && !target.containsKey("camera_frame")) reject(MISSING_REQUIRED_FIELD, targetPath + ".camera_frame", "optical camera geometry needs its named frame");
            if (targetValid && (!geometryValid || !verified || target.get("invalid_reason") != null
                    || target.get("camera_translation_m") == null || target.get("tx_deg") == null || target.get("ty_deg") == null)) reject(OUT_OF_RANGE, targetPath, "valid POI lacks verified usable geometry");
            if (targetValid && !detectionIds.contains(tagId)) reject(BROKEN_REFERENCE, targetPath + ".tag_id", "valid POI tag absent from current detections");
            if (name.equals(selected) && targetValid) selectedValid = true;
        }
        if (valid != (selected != null) || valid && !selectedValid) reject(BROKEN_REFERENCE, path + ".selected_name", "selection must reference a valid POI");
    }

    private void validateTiming(Map<String, Object> timing) throws DecodeException {
        String path = "$.timing";
        constant(required(timing, "clock_domain", path), path + ".clock_domain", "nt_server", OUT_OF_RANGE);
        constant(required(timing, "timestamp_unit", path), path + ".timestamp_unit", "us", OUT_OF_RANGE);
        constant(required(timing, "capture_event", path), path + ".capture_event", "host_frame_read_complete", OUT_OF_RANGE);
        bool(required(timing, "capture_correction_verified", path), path + ".capture_correction_verified");
        nullableNumber(required(timing, "capture_correction_uncertainty_ms", path), path + ".capture_correction_uncertainty_ms", 0, Double.MAX_VALUE);
    }

    private void validateDiagnostics(Map<String, Object> value) throws DecodeException {
        for (String key : List.of("processing_ms", "detector_ms", "localization_ms", "queue_ms", "fps", "single_tag_pose_ms")) optionalNumber(value, key, "$", 0, Double.MAX_VALUE, false);
        optionalInteger(value, "dropped_frames", "$", 0, Long.MAX_VALUE);
        if (value.containsKey("frame_size")) integerVector(value.get("frame_size"), "$.frame_size", 2, 1);
        if (value.containsKey("preview_settings")) object(value.get("preview_settings"), "$.preview_settings");
        for (String key : List.of("native_timings", "inference_timings")) {
            if (value.containsKey(key)) timingNumbers(object(value.get(key), "$." + key), "$." + key);
        }
    }

    private void timingNumbers(Map<String, Object> value, String path) throws DecodeException {
        for (Map.Entry<String, Object> entry : value.entrySet()) number(entry.getValue(), path + "." + entry.getKey(), 0, Double.MAX_VALUE);
    }

    private void covariance(Object value, String path) throws DecodeException {
        double[][] matrix = matrix(value, path, 2, 2);
        for (int i = 0; i < 2; i++) {
            if (matrix[i][i] < 0) reject(INVALID_COVARIANCE, path, "negative covariance diagonal");
            for (int j = i + 1; j < 2; j++) {
                double symmetryTolerance = 16 * Math.ulp(Math.max(Math.abs(matrix[i][j]), Math.abs(matrix[j][i])));
                if (Math.abs(matrix[i][j] - matrix[j][i]) > symmetryTolerance) reject(INVALID_COVARIANCE, path, "asymmetric covariance");
            }
        }
        // The actual producer contract supplies 2x2 XY covariance. Its PSD condition is
        // |off diagonal| <= sqrt(xx)*sqrt(yy); square roots avoid product overflow and
        // retain the decisive sign even with extreme unequal scales or zero variance.
        // Allow 16 representable-number steps for arithmetic roundoff, not a tolerance
        // scaled to an unrelated large diagonal that could hide a negative eigenvalue.
        double bound = Math.sqrt(matrix[0][0]) * Math.sqrt(matrix[1][1]);
        double offDiagonal = Math.max(Math.abs(matrix[0][1]), Math.abs(matrix[1][0]));
        double arithmeticTolerance = 16 * Math.ulp(Math.max(bound, offDiagonal));
        if (offDiagonal - bound > arithmeticTolerance) reject(INVALID_COVARIANCE, path, "covariance is not positive semidefinite");
    }

    private Set<Long> idSet(Object value, String path) throws DecodeException {
        Set<Long> ids = new HashSet<>();
        for (Object item : array(value, path, limits.maxDetections())) {
            if (!ids.add(integer(item, path, 0, Long.MAX_VALUE))) reject(BROKEN_REFERENCE, path, "duplicate referenced ID");
        }
        return ids;
    }
    private static Object required(Map<String, Object> value, String key, String path) throws DecodeException {
        if (!value.containsKey(key)) reject(MISSING_REQUIRED_FIELD, path + "." + key, "required field missing");
        return value.get(key);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String path) throws DecodeException {
        if (!(value instanceof Map<?, ?>)) reject(WRONG_TYPE, path, "expected object");
        return (Map<String, Object>) value;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String path, int max) throws DecodeException {
        if (!(value instanceof List<?>)) reject(WRONG_TYPE, path, "expected array");
        List<Object> list = (List<Object>) value;
        if (list.size() > max) reject(COLLECTION_LIMIT, path, "array count exceeds limit " + max);
        return list;
    }
    private static String text(Object value, String path, boolean nonempty) throws DecodeException {
        if (!(value instanceof String)) reject(WRONG_TYPE, path, "expected string");
        String text = (String) value;
        if (nonempty && text.isEmpty()) reject(OUT_OF_RANGE, path, "empty identity/string");
        return text;
    }
    private static void nullableText(Object value, String path) throws DecodeException { if (value != null) text(value, path, false); }
    private static boolean bool(Object value, String path) throws DecodeException {
        if (!(value instanceof Boolean)) reject(WRONG_TYPE, path, "expected boolean");
        return (Boolean) value;
    }
    private static long integer(Object value, String path, long low, long high) throws DecodeException {
        if (!(value instanceof Long)) reject(WRONG_TYPE, path, "expected integer JSON token; numeric coercions are forbidden");
        long result = (Long) value;
        if (result < low || result > high) reject(OUT_OF_RANGE, path, "integer outside [" + low + "," + high + "]");
        return result;
    }
    private static double number(Object value, String path) throws DecodeException {
        if (!(value instanceof Long) && !(value instanceof Double)) reject(WRONG_TYPE, path, "expected numeric JSON token");
        double result = ((Number) value).doubleValue();
        if (!Double.isFinite(result)) reject(NONFINITE_NUMBER, path, "nonfinite number");
        return result;
    }
    private static double number(Object value, String path, double low, double high) throws DecodeException {
        double result = number(value, path);
        if (result < low || result > high) reject(OUT_OF_RANGE, path, "number outside supported range");
        return result;
    }
    private static void nullableNumber(Object value, String path, double low, double high) throws DecodeException { if (value != null) number(value, path, low, high); }
    private static void enumText(Object value, String path, Set<String> allowed) throws DecodeException {
        if (!allowed.contains(text(value, path, true))) reject(OUT_OF_RANGE, path, "unsupported enum value");
    }
    private static void constant(Object value, String path, Object expected, DecodeException.Reason reason) throws DecodeException {
        if (!Objects.equals(expected, value)) reject(reason, path, "expected " + expected);
    }
    private double[] vector(Object value, String path, int size) throws DecodeException {
        List<Object> entries = array(value, path, limits.maxArrayEntries());
        if (entries.size() != size) reject(BAD_DIMENSION, path, "expected vector length " + size);
        double[] result = new double[size];
        for (int i = 0; i < size; i++) result[i] = number(entries.get(i), path + "[" + i + "]");
        return result;
    }
    private void nullableVector(Object value, String path, int size) throws DecodeException { if (value != null) vector(value, path, size); }
    private double[][] matrix(Object value, String path, int rows, int columns) throws DecodeException {
        List<Object> entries = array(value, path, limits.maxArrayEntries());
        if (entries.size() != rows) reject(BAD_DIMENSION, path, "expected matrix row count " + rows);
        double[][] result = new double[rows][];
        for (int i = 0; i < rows; i++) result[i] = vector(entries.get(i), path + "[" + i + "]", columns);
        return result;
    }
    private void integerVector(Object value, String path, int size, long low) throws DecodeException {
        List<Object> entries = array(value, path, limits.maxArrayEntries());
        if (entries.size() != size) reject(BAD_DIMENSION, path, "expected vector length " + size);
        for (int i = 0; i < size; i++) integer(entries.get(i), path + "[" + i + "]", low, Long.MAX_VALUE);
    }
    private void optionalVector(Map<String, Object> value, String key, String path, int size, boolean nullable) throws DecodeException {
        if (value.containsKey(key) && (!nullable || value.get(key) != null)) vector(value.get(key), path + "." + key, size);
    }
    private static void optionalText(Map<String, Object> value, String key, String path, boolean nullable) throws DecodeException {
        if (value.containsKey(key) && (!nullable || value.get(key) != null)) text(value.get(key), path + "." + key, false);
    }
    private static void optionalBool(Map<String, Object> value, String key, String path) throws DecodeException { if (value.containsKey(key)) bool(value.get(key), path + "." + key); }
    private static void optionalInteger(Map<String, Object> value, String key, String path, long low, long high) throws DecodeException { if (value.containsKey(key)) integer(value.get(key), path + "." + key, low, high); }
    private static void optionalNumber(Map<String, Object> value, String key, String path, double low, double high, boolean nullable) throws DecodeException { if (value.containsKey(key) && (!nullable || value.get(key) != null)) number(value.get(key), path + "." + key, low, high); }
    private static void reject(DecodeException.Reason reason, String path, String detail) throws DecodeException { throw new DecodeException(reason, path, detail); }
}
