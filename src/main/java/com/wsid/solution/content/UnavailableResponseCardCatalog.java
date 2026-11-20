package com.wsid.solution.content;

import com.wsid.solution.domain.ResponseCard;
import com.wsid.solution.domain.ResponseCardCatalog;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 운영용 검토 카드가 아직 없을 때의 기본 카탈로그. 어떤 카드도 돌려주지 않아요.
 * 카드가 필요한 사실 조합은 503 RESPONSE_CONTENT_UNAVAILABLE이 되고, 카드 없는 조합만 정상 시작해요.
 * LSH 합의 3 뒤 GuideCatalog 기반 구현으로 교체해요.
 */
@Component
public class UnavailableResponseCardCatalog implements ResponseCardCatalog {
    @Override
    public Optional<ResponseCard> findReviewed(String code) {
        return Optional.empty();
    }
}
