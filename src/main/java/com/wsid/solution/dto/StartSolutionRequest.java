package com.wsid.solution.dto;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * SOL-01 요청. 허용 키는 description 하나이고 문자열만 받아요(숫자·boolean 자동 변환 없음).
 * 본문 없음·{}·null·빈/공백 설명은 모두 선택형 시작이에요.
 */
public final class StartSolutionRequest {
    public static final int MAX_DESCRIPTION_LENGTH = 1_000;
    private static final StartSolutionRequest EMPTY = new StartSolutionRequest(null);

    private final String description;

    private StartSolutionRequest(String description) {
        this.description = description;
    }

    public static StartSolutionRequest empty() {
        return EMPTY;
    }

    /** @param body 본문이 없으면 null. 객체가 아니거나 허용하지 않은 키·타입이면 400이에요. */
    public static StartSolutionRequest from(JsonNode body) {
        if (body == null || body.isMissingNode()) return EMPTY;
        if (!body.isObject()) throw new ApiException(ErrorCode.INVALID_REQUEST);
        for (String name : body.propertyNames()) {
            if (!"description".equals(name)) throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        JsonNode value = body.get("description");
        if (value == null || value.isNull()) return EMPTY;
        if (!value.isString()) throw new ApiException(ErrorCode.INVALID_REQUEST);
        String text = value.stringValue();
        if (text.length() > MAX_DESCRIPTION_LENGTH) throw new ApiException(ErrorCode.INVALID_REQUEST);
        return text.isBlank() ? EMPTY : new StartSolutionRequest(text);
    }

    /** 설명은 AI 요청에만 쓰고 저장·로그에 남기지 않아요. */
    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    @Override
    public String toString() {
        return "StartSolutionRequest[descriptionLength=" + (description == null ? 0 : description.length()) + "]";
    }
}
