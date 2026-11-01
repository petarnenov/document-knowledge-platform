package org.documentknowledge.inspector;

public final class InspectionFailure extends RuntimeException {
    private final String code;
    public InspectionFailure(String code) { super(code);this.code=code; }
    public String code() { return code; }
}
