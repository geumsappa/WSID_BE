package com.wsid.content.dto;

import com.wsid.content.model.FraudType;

/** 공개 목록에는 검토 메타데이터를 포함하지 않아요. */
public record FraudTypeSummaryResponse(String code, String name, String description) {
    public static FraudTypeSummaryResponse from(FraudType type) {
        return new FraudTypeSummaryResponse(type.code(), type.name(), type.description());
    }
}
