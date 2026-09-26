package dev.codesprint.explain;

import java.util.Optional;

/** 꺼진 Explain Back. 기본값이다 - 켜지 않으면 설명을 받아도 분석하지 않는다(503). */
public class DisabledExplainer implements ExplainPort {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<Analysis> analyze(Request request) {
        return Optional.empty();
    }

    @Override
    public String promptVersion() {
        return null;
    }
}
