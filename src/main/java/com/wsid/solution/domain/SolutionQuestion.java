package com.wsid.solution.domain;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 다음에 물어볼 고정 질문. 선택지는 YES/NO/UNKNOWN 코드예요. */
public record SolutionQuestion(String factKey, String content, List<String> options) {
    public static final List<String> OPTIONS = List.of("YES", "NO", "UNKNOWN");

    public SolutionQuestion {
        options = List.copyOf(options);
    }

    /** 첫 UNKNOWN 항목을 물어요. 모두 확정이면 질문이 없고 상담은 ACTIVE로 남아요. */
    public static Optional<SolutionQuestion> next(SolutionFacts facts) {
        return next(facts, Set.of());
    }

    /** UNKNOWN으로 답을 마친 항목은 이번 상담에서 반복 질문하지 않아요. */
    public static Optional<SolutionQuestion> next(SolutionFacts facts, Set<SolutionFact> answeredUnknown) {
        for (SolutionFact fact : SolutionFact.values()) {
            if (facts.get(fact) == FactValue.UNKNOWN && !answeredUnknown.contains(fact)) {
                return Optional.of(new SolutionQuestion(fact.key(), fact.question(), OPTIONS));
            }
        }
        return Optional.empty();
    }
}
