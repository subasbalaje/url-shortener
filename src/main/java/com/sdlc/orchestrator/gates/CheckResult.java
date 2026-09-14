package com.sdlc.orchestrator.gates;

public record CheckResult(boolean passed, String message) {
    public static CheckResult pass() { return new CheckResult(true, ""); }
    public static CheckResult fail(String msg) { return new CheckResult(false, msg); }
}
