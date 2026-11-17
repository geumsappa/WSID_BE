package com.wsid.solution.domain;

import java.util.Optional;

/**
 * 상담에서 확인하는 4개 사실. 선언 순서가 공개 응답의 키 순서이자 질문 순서예요.
 * 질문 문구는 서버가 고정한 초안이며 콘텐츠 담당 검토 대상이에요.
 */
public enum SolutionFact {
    TRANSFERRED_MONEY("transferredMoney", "상대방에게 돈을 보낸 적이 있나요?"),
    SHARED_INFORMATION("sharedInformation", "개인정보를 전달한 적이 있나요?"),
    INSTALLED_APP("installedApp", "상대방이 알려준 앱을 설치한 적이 있나요?"),
    CONTACT_CONTINUES("contactContinues", "상대방과 지금도 연락하고 있나요?");

    private final String key;
    private final String question;

    SolutionFact(String key, String question) {
        this.key = key;
        this.question = question;
    }

    public String key() {
        return key;
    }

    public String question() {
        return question;
    }

    public static Optional<SolutionFact> fromKey(String key) {
        for (SolutionFact fact : values()) {
            if (fact.key.equals(key)) return Optional.of(fact);
        }
        return Optional.empty();
    }
}
