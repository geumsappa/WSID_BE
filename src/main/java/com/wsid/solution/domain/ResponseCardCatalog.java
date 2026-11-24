package com.wsid.solution.domain;

import java.util.Optional;

/**
 * 검토된 대응 카드 조회 경계. GuideCatalog가 읽은 guides.yml의 공개 카드와 버전을 사용해요.
 * 미검토 카드는 돌려주지 않아요.
 */
public interface ResponseCardCatalog {
    Optional<ResponseCard> findReviewed(String code);
}
