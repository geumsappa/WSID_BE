package com.wsid.solution.dto;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.FactValue;
import com.wsid.solution.domain.SolutionFact;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** SOL-03 공개 입력. 원문은 AI 전송에만 사용하며 toString에도 남기지 않아요. */
public final class SendSolutionMessageRequest {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<String> TEXT_KEYS = Set.of("requestId", "stateVersion", "inputKind", "content");
    private static final Set<String> CHOICE_KEYS = Set.of("requestId", "stateVersion", "inputKind", "factKey", "selectedCode");
    public enum Purpose { FACT_UPDATE, QUESTION, CLARIFICATION_ANSWER }
    private Purpose purpose = Purpose.FACT_UPDATE;
    private UUID questionId;
    private final UUID requestId;
    private final int stateVersion;
    private final String content;
    private final SolutionFact fact;
    private final FactValue selected;

    private SendSolutionMessageRequest(UUID requestId, int stateVersion, String content,
            SolutionFact fact, FactValue selected) {
        this.requestId = requestId;
        this.stateVersion = stateVersion;
        this.content = content;
        this.fact = fact;
        this.selected = selected;
    }

    public static SendSolutionMessageRequest from(JsonNode body) {
        if (body == null || !body.isObject()) throw invalid();
        if (body.has("purpose")) {
            Purpose purpose;
            try { purpose = Purpose.valueOf(string(body, "purpose")); }
            catch (IllegalArgumentException e) { throw invalid(); }
            var copy = ((tools.jackson.databind.node.ObjectNode) body).deepCopy();
            copy.remove("purpose");
            if (purpose == Purpose.CLARIFICATION_ANSWER) {
                if (!"CHOICE".equals(string(copy, "inputKind")) || copy.has("factKey")) throw invalid();
                String questionId = string(copy, "questionId");
                if (!UUID_TEXT.matcher(questionId).matches()) throw invalid();
                if (copy.size() != 5) throw invalid();
                for (String key : copy.propertyNames())
                    if (!Set.of("requestId", "stateVersion", "inputKind", "questionId", "selectedCode").contains(key)) throw invalid();
                String requestId = string(copy, "requestId");
                if (!UUID_TEXT.matcher(requestId).matches()) throw invalid();
                JsonNode version = copy.get("stateVersion");
                if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                        || version.intValue() < 0 || version.intValue() == Integer.MAX_VALUE) throw invalid();
                FactValue selected;
                try { selected = FactValue.valueOf(string(copy, "selectedCode")); }
                catch (IllegalArgumentException e) { throw invalid(); }
                var result = new SendSolutionMessageRequest(UUID.fromString(requestId), version.intValue(), null, null, selected);
                result.purpose = purpose;
                result.questionId = UUID.fromString(questionId);
                return result;
            }
            if (purpose == Purpose.QUESTION && !"TEXT".equals(string(copy, "inputKind"))) throw invalid();
            var result = from(copy);
            result.purpose = purpose;
            return result;
        }
        String id = string(body, "requestId");
        if (!UUID_TEXT.matcher(id).matches()) throw invalid();
        JsonNode version = body.get("stateVersion");
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                || version.intValue() < 0 || version.intValue() == Integer.MAX_VALUE) throw invalid();
        String kind = string(body, "inputKind");
        boolean text = "TEXT".equals(kind);
        Set<String> allowed = text ? TEXT_KEYS : CHOICE_KEYS;
        if (!text && !"CHOICE".equals(kind)) throw invalid();
        if (body.size() != allowed.size()) throw invalid();
        for (String key : body.propertyNames()) if (!allowed.contains(key)) throw invalid();
        if (text) {
            String content = string(body, "content");
            if (content.isBlank() || content.length() > StartSolutionRequest.MAX_DESCRIPTION_LENGTH) throw invalid();
            return new SendSolutionMessageRequest(UUID.fromString(id), version.intValue(), content, null, null);
        }
        SolutionFact fact = SolutionFact.fromKey(string(body, "factKey")).orElseThrow(SendSolutionMessageRequest::invalid);
        FactValue selected;
        try {
            selected = FactValue.valueOf(string(body, "selectedCode"));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        return new SendSolutionMessageRequest(UUID.fromString(id), version.intValue(), null, fact, selected);
    }

    /**
     * 사진에서 읽은 글자를 SOL-03 텍스트 입력과 같은 흐름으로 보내기 위한 요청이에요(SOL-06).
     * 글자는 공개 입력과 같은 길이 제한을 따라요.
     */
    public static SendSolutionMessageRequest recognizedText(UUID requestId, int stateVersion, String content) {
        if (requestId == null || stateVersion < 0 || stateVersion == Integer.MAX_VALUE
                || content == null || content.isBlank() || content.length() > StartSolutionRequest.MAX_DESCRIPTION_LENGTH) {
            throw invalid();
        }
        return new SendSolutionMessageRequest(requestId, stateVersion, content, null, null);
    }

    private static String string(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isString()) throw invalid();
        return value.stringValue();
    }

    private static ApiException invalid() { return new ApiException(ErrorCode.INVALID_REQUEST); }

    public Purpose purpose() { return purpose; }
    public UUID questionId() { return questionId; }
    public UUID requestId() { return requestId; }
    public int stateVersion() { return stateVersion; }
    public boolean isText() { return content != null; }
    public String content() { return content; }
    public SolutionFact fact() { return fact; }
    public FactValue selected() { return selected; }

    @Override public String toString() {
        return "SendSolutionMessageRequest[inputKind=" + (isText() ? "TEXT" : "CHOICE")
                + ", contentLength=" + (content == null ? 0 : content.length()) + ", requestId=" + requestId + "]";
    }
}
