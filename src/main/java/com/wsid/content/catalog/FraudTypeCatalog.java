package com.wsid.content.catalog;

import com.wsid.content.model.FraudType;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** 기동 시 구성하는 불변 스냅샷. 조회 중에는 파일이나 DB를 읽지 않아요. */
public final class FraudTypeCatalog {
    private final List<FraudType> reviewedTypes;

    public FraudTypeCatalog(List<FraudType> types) {
        if (types == null) throw new IllegalArgumentException("유형 목록이 필요해요.");
        var codes = new HashSet<String>();
        for (FraudType type : types) {
            if (type == null || !codes.add(type.code())) {
                throw new IllegalArgumentException("사기 유형이 없거나 코드가 중복돼요.");
            }
        }
        reviewedTypes = types.stream().filter(FraudType::reviewed)
                .sorted(Comparator.comparingInt(FraudType::displayOrder).thenComparing(FraudType::code)).toList();
    }

    public List<FraudType> findAllReviewed() {
        return reviewedTypes;
    }
}
