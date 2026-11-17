package com.wsid.solution.dto;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.FactValue;
import com.wsid.solution.domain.SolutionFact;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** SOL-04 공개 입력. 허용 사실 키·YES/NO/UNKNOWN 문자열만 받고, 하나라도 틀리면 전체를 거부해요. */
public final class UpdateSolutionFactsRequest {
    private static final Set<String> KEYS = Set.of("stateVersion", "changes");
    private final int stateVersion;
    private final Map<SolutionFact, FactValue> changes;

    private UpdateSolutionFactsRequest(int stateVersion, Map<SolutionFact, FactValue> changes) {
        this.stateVersion = stateVersion;
        this.changes = Collections.unmodifiableMap(new EnumMap<>(changes));
    }

    public static UpdateSolutionFactsRequest from(JsonNode body) {
        if (body == null || !body.isObject() || body.size() != KEYS.size()) throw invalid();
        for (String key : body.propertyNames()) if (!KEYS.contains(key)) throw invalid();
        JsonNode version = body.get("stateVersion");
        if (!version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() < 0 || version.intValue() == Integer.MAX_VALUE) throw invalid();
        JsonNode raw = body.get("changes");
        if (!raw.isObject() || raw.isEmpty() || raw.size() > SolutionFact.values().length) throw invalid();
        var changes = new EnumMap<SolutionFact, FactValue>(SolutionFact.class);
        for (String key : raw.propertyNames()) {
            SolutionFact fact = SolutionFact.fromKey(key).orElseThrow(UpdateSolutionFactsRequest::invalid);
            JsonNode value = raw.get(key);
            if (!value.isString()) throw invalid();
            try {
                changes.put(fact, FactValue.valueOf(value.stringValue()));
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
        }
        return new UpdateSolutionFactsRequest(version.intValue(), changes);
    }

    private static ApiException invalid() { return new ApiException(ErrorCode.INVALID_REQUEST); }

    public int stateVersion() { return stateVersion; }
    /** SolutionFact 선언 순서로 정렬된 불변 Map이에요. */
    public Map<SolutionFact, FactValue> changes() { return changes; }

    @Override public String toString() {
        return "UpdateSolutionFactsRequest[stateVersion=" + stateVersion + ", changeCount=" + changes.size() + "]";
    }
}
