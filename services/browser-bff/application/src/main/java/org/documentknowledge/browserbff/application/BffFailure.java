package org.documentknowledge.browserbff.application;

/** Public failure categories contain no provider diagnostics or credential values. */
public final class BffFailure extends RuntimeException {
    private final int status;
    private final String code;
    public BffFailure(int status, String code) { super(code); this.status = status; this.code = code.toUpperCase(java.util.Locale.ROOT); }
    public int status() { return status; }
    public String code() { return code; }
}
