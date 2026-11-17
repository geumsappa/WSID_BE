package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.domain.FactValue;
import com.wsid.solution.domain.SolutionFact;
import com.wsid.solution.dto.UpdateSolutionFactsRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class UpdateSolutionFactsRequestTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode body(int version, String... keyValues) {
        var node = JSON.createObjectNode().put("stateVersion", version);
        var changes = node.putObject("changes");
        for (int i = 0; i < keyValues.length; i += 2) changes.put(keyValues[i], keyValues[i + 1]);
        return node;
    }

    @Test void acceptsOneOrAllFactsInDeclarationOrderAndHidesValuesInToString() {
        var one = UpdateSolutionFactsRequest.from(body(3, "installedApp", "YES"));
        assertEquals(3, one.stateVersion());
        assertEquals(FactValue.YES, one.changes().get(SolutionFact.INSTALLED_APP));
        var all = UpdateSolutionFactsRequest.from(body(0, "contactContinues", "NO", "installedApp", "UNKNOWN",
                "sharedInformation", "YES", "transferredMoney", "NO"));
        assertEquals(List.of(SolutionFact.values()), List.copyOf(all.changes().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> all.changes().clear());
        assertEquals("UpdateSolutionFactsRequest[stateVersion=0, changeCount=4]", all.toString());
        assertEquals(2_147_483_646, UpdateSolutionFactsRequest.from(body(2_147_483_646, "installedApp", "NO")).stateVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null", "[]", "\"x\"", "{}",
            "{\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":1}",
            "{\"stateVersion\":1,\"changes\":{}}",
            "{\"stateVersion\":1,\"changes\":null}",
            "{\"stateVersion\":1,\"changes\":[]}",
            "{\"stateVersion\":1,\"changes\":\"installedApp\"}",
            "{\"stateVersion\":\"1\",\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":1.0,\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":true,\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":null,\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":-1,\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":2147483647,\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":2147483648,\"changes\":{\"installedApp\":\"YES\"}}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":\"yes\"}}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":\" YES\"}}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":true}}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":null}}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":1}}",
            "{\"stateVersion\":1,\"changes\":{\"InstalledApp\":\"YES\"}}",
            "{\"stateVersion\":1,\"changes\":{\"accountNumber\":\"YES\"}}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":\"YES\",\"bad\":\"NO\"}}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":\"YES\"},\"requestId\":\"x\"}",
            "{\"stateVersion\":1,\"changes\":{\"installedApp\":\"YES\"},\"content\":\"x\"}"
    })
    void rejectsWholeRequestOnAnyInvalidPart(String raw) {
        var error = assertThrows(ApiException.class, () -> UpdateSolutionFactsRequest.from(JSON.readTree(raw)));
        assertEquals(ErrorCode.INVALID_REQUEST, error.errorCode());
    }

    @Test void rejectsMissingBodyAndMoreThanFourChanges() {
        assertThrows(ApiException.class, () -> UpdateSolutionFactsRequest.from(null));
        var five = body(1, "installedApp", "YES");
        ((ObjectNode) five.get("changes")).put("transferredMoney", "NO").put("sharedInformation", "NO")
                .put("contactContinues", "NO").put("extra", "NO");
        assertThrows(ApiException.class, () -> UpdateSolutionFactsRequest.from(five));
    }
}
