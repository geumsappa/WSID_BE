package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.dto.SendSolutionMessageRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class SendSolutionMessageRequestTest {
    private final ObjectMapper json = new ObjectMapper();

    static ObjectNode choice(UUID id, int version, String key, String value) {
        return new ObjectMapper().createObjectNode().put("requestId", id.toString()).put("stateVersion", version)
                .put("inputKind", "CHOICE").put("factKey", key).put("selectedCode", value);
    }

    static ObjectNode text(UUID id, int version, String content) {
        return new ObjectMapper().createObjectNode().put("requestId", id.toString()).put("stateVersion", version)
                .put("inputKind", "TEXT").put("content", content);
    }

    @Test void acceptsCanonicalUppercaseUuidAndDoesNotLogContent() {
        UUID id = UUID.randomUUID();
        var node = text(id, 0, "비공개 상세 설명").put("requestId", id.toString().toUpperCase());
        var input = SendSolutionMessageRequest.from(node);
        assertEquals(id, input.requestId());
        assertTrue(input.isText());
        assertEquals("비공개 상세 설명", input.content());
        assertFalse(input.toString().contains(input.content()));
        assertEquals(0, input.stateVersion());
        var selected = SendSolutionMessageRequest.from(choice(id, 2, "sharedInformation", "NO"));
        assertFalse(selected.isText());
        assertEquals("sharedInformation", selected.fact().key());
        assertEquals("NO", selected.selected().name());
    }

    @ParameterizedTest @ValueSource(strings = {"1-1-1-1-1", "uuid", "", "00000000-0000-0000-0000-00000000000g", " 00000000-0000-0000-0000-000000000000"})
    void rejectsNonCanonicalUuid(String raw) { invalid(text(UUID.randomUUID(), 0, "설명").put("requestId", raw)); }

    @ParameterizedTest @ValueSource(strings = {"\"2\"", "2.0", "-1", "2147483648", "2147483647", "true", "null"})
    void rejectsInvalidVersion(String raw) { invalid(text(UUID.randomUUID(), 0, "설명").set("stateVersion", json.readTree(raw))); }

    @ParameterizedTest @ValueSource(strings = {"ACTION", "OPTION", "text", "", "true"})
    void rejectsUnsupportedKind(String raw) { invalid(text(UUID.randomUUID(), 0, "설명").put("inputKind", raw)); }

    @ParameterizedTest @ValueSource(strings = {"requestId", "stateVersion", "inputKind", "content"})
    void requiresEveryTextField(String key) {
        var node = text(UUID.randomUUID(), 0, "설명");
        node.remove(key); invalid(node);
        node = text(UUID.randomUUID(), 0, "설명"); node.putNull(key); invalid(node);
    }

    @ParameterizedTest @ValueSource(strings = {"requestId", "stateVersion", "inputKind", "factKey", "selectedCode"})
    void requiresEveryChoiceField(String key) {
        var node = choice(UUID.randomUUID(), 0, "installedApp", "YES");
        node.remove(key); invalid(node);
        node = choice(UUID.randomUUID(), 0, "installedApp", "YES"); node.putNull(key); invalid(node);
    }

    @Test void rejectsCrossFieldsWrongTypesAndUnknownFields() {
        invalid(choice(UUID.randomUUID(), 0, "installedApp", "NO").putNull("content"));
        invalid(text(UUID.randomUUID(), 0, "설명").put("factKey", "installedApp"));
        invalid(text(UUID.randomUUID(), 0, "설명").put("userId", 1));
        invalid(text(UUID.randomUUID(), 0, "설명").put("content", 42));
        invalid(choice(UUID.randomUUID(), 0, "unknown", "YES"));
        invalid(choice(UUID.randomUUID(), 0, "installedApp", "yes"));
        invalid(choice(UUID.randomUUID(), 0, "installedApp", "YES").put("selectedCode", false));
        invalid(null); invalid(json.readTree("[]")); invalid(json.readTree("null"));
    }

    @Test void checksOriginalLengthAndBlankContent() {
        assertNotNull(SendSolutionMessageRequest.from(text(UUID.randomUUID(), 0, "가".repeat(1000))));
        invalid(text(UUID.randomUUID(), 0, "가".repeat(1001)));
        invalid(text(UUID.randomUUID(), 0, " ".repeat(1000)));
        invalid(text(UUID.randomUUID(), 0, ""));
        invalid(text(UUID.randomUUID(), 0, " ".repeat(1000) + "가"));
    }

    private void invalid(tools.jackson.databind.JsonNode node) {
        assertEquals(ErrorCode.INVALID_REQUEST, assertThrows(ApiException.class,
                () -> SendSolutionMessageRequest.from(node)).errorCode());
    }
}
