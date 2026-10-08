package org.customvision.protocol;

import java.util.Objects;

/** Configured complete result-topic namespace plus the expected payload identity. */
public record SourceKey(String namespace, String pipeline, String type) {
    public SourceKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(type, "type");
        if (!namespace.startsWith("/") || namespace.length() > 512 || namespace.endsWith("/")
                || namespace.contains("//") || namespace.chars().anyMatch(c -> c < 0x20)
                || namespace.equals("/")) {
            throw new IllegalArgumentException("namespace must be a full canonical topic namespace");
        }
        if (pipeline.isEmpty() || pipeline.length() > 128 || pipeline.contains("/")
                || pipeline.chars().anyMatch(c -> c < 0x20)) {
            throw new IllegalArgumentException("pipeline must be a nonempty bounded topic segment");
        }
        if (!type.equals("apriltag") && !type.equals("object")) {
            throw new IllegalArgumentException("type must be apriltag or object");
        }
    }

    public String resultTopic() { return namespace + "/result"; }
}
