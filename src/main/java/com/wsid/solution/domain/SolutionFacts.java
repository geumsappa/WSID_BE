package com.wsid.solution.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 4개 사실을 항상 모두 가진 불변 값. 외부 문자열은 허용된 키·값만 받아요. */
public final class SolutionFacts {
    private final Map<SolutionFact, FactValue> values;

    private SolutionFacts(Map<SolutionFact, FactValue> values) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
    }

    public static SolutionFacts unknown() {
        var values = new EnumMap<SolutionFact, FactValue>(SolutionFact.class);
        for (SolutionFact fact : SolutionFact.values()) values.put(fact, FactValue.UNKNOWN);
        return new SolutionFacts(values);
    }

    /** AI-06 결과처럼 4개 키가 모두 있는 문자열 Map을 변환해요. 키가 빠지거나 남으면 거부해요. */
    public static SolutionFacts fromStrings(Map<String, String> raw) {
        if (raw == null || raw.size() != SolutionFact.values().length) {
            throw new IllegalArgumentException("사실 항목이 올바르지 않아요.");
        }
        var values = new EnumMap<SolutionFact, FactValue>(SolutionFact.class);
        raw.forEach((key, value) -> {
            SolutionFact fact = SolutionFact.fromKey(key)
                    .orElseThrow(() -> new IllegalArgumentException("알 수 없는 사실 항목이에요."));
            values.put(fact, parse(value));
        });
        if (values.size() != SolutionFact.values().length) {
            throw new IllegalArgumentException("사실 항목이 올바르지 않아요.");
        }
        return new SolutionFacts(values);
    }

    private static FactValue parse(String value) {
        if (value == null) throw new IllegalArgumentException("사실 값이 올바르지 않아요.");
        for (FactValue candidate : FactValue.values()) {
            if (candidate.name().equals(value)) return candidate;
        }
        throw new IllegalArgumentException("사실 값이 올바르지 않아요.");
    }

    public FactValue get(SolutionFact fact) {
        return values.get(fact);
    }

    /** 질문 순서상 첫 UNKNOWN. 모두 확정이면 비어 있어요. */
    public Optional<SolutionFact> firstUnknown() {
        for (SolutionFact fact : SolutionFact.values()) {
            if (values.get(fact) == FactValue.UNKNOWN) return Optional.of(fact);
        }
        return Optional.empty();
    }

    /** 공개 응답·DB 저장용. 키 순서는 SolutionFact 선언 순서예요. */
    public Map<String, String> toKeyMap() {
        var map = new LinkedHashMap<String, String>();
        for (SolutionFact fact : SolutionFact.values()) map.put(fact.key(), values.get(fact).name());
        return Collections.unmodifiableMap(map);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SolutionFacts facts && values.equals(facts.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public String toString() {
        return "SolutionFacts" + toKeyMap();
    }
}
