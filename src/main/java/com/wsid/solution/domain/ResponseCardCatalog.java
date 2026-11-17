package com.wsid.solution.domain;

import java.util.Optional;

/**
 * 검토된 대응 카드 조회 경계. 실제 카드 콘텐츠·로더는 LSH 합의 3(카드 카탈로그 소유와 형식) 뒤에 연결해요.
 * 미검토 카드는 돌려주지 않아요.
 */
public interface ResponseCardCatalog {
    Optional<ResponseCard> findReviewed(String code);
}
