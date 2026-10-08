package org.customvision.protocol;

/** A stable machine-readable rejection. Rejected packets produce no partial measurement. */
public final class DecodeException extends Exception {
    private static final long serialVersionUID = 1L;
    public enum Reason {
        MALFORMED_JSON, DUPLICATE_KEY, PAYLOAD_LIMIT, STRING_LIMIT, TOKEN_LIMIT,
        NESTING_LIMIT, COLLECTION_LIMIT, MISSING_REQUIRED_FIELD, WRONG_TYPE,
        UNSUPPORTED_VERSION, SOURCE_MISMATCH, OUT_OF_RANGE, INTEGER_OVERFLOW,
        NONFINITE_NUMBER, BAD_DIMENSION, INVALID_QUATERNION, ILLEGAL_FRAME,
        INVALID_COVARIANCE, BROKEN_REFERENCE
    }

    private final Reason reason;
    private final String path;
    private final int offset;

    public DecodeException(Reason reason, String path, String detail) {
        this(reason, path, -1, detail);
    }

    public DecodeException(Reason reason, String path, int offset, String detail) {
        super(reason + " at " + path + (offset < 0 ? "" : " (character " + offset + ")") + ": " + detail);
        this.reason = reason;
        this.path = path;
        this.offset = offset;
    }

    public Reason reason() { return reason; }
    public String path() { return path; }
    public int offset() { return offset; }
}
