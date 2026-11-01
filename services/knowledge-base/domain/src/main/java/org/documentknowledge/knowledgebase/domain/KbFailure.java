package org.documentknowledge.knowledgebase.domain;

/** Finite safe domain error; adapters serialize only code. */
public final class KbFailure extends RuntimeException {
    private final int status;
    private final String code;
    public KbFailure(int status, String code) { super(code); this.status = status; this.code = code; }
    public int status() { return status; }
    public String code() { return code; }
    public static KbFailure unavailable() { return new KbFailure(503, "UNAVAILABLE"); }
    public static KbFailure hidden() { return new KbFailure(404, "NOT_FOUND"); }
    public static KbFailure stale() { return new KbFailure(409, "STALE_POLICY"); }
}
