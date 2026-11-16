package com.wsid.content;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.content.catalog.FraudTypeCatalog;
import com.wsid.content.dto.FraudTypeSummaryResponse;
import com.wsid.content.service.FraudTypeQueryService;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudTypeQueryServiceTest {
    @Test
    void returnsPublicFieldsInCatalogOrderAndDoesNotExposeUnreviewedItems() {
        var service = new FraudTypeQueryService(new FraudTypeCatalog(List.of(
                FraudTypeCatalogTest.type("B", 2, true), FraudTypeCatalogTest.type("DRAFT", 0, false),
                FraudTypeCatalogTest.type("A", 1, true))));
        var result = service.findAll();
        assertEquals(List.of(new FraudTypeSummaryResponse("A", "제목", "설명"),
                new FraudTypeSummaryResponse("B", "제목", "설명")), result);
        assertEquals(result, service.findAll());
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void returnsEmptyListWhenNoApprovedContentExists() {
        assertEquals(List.of(), new FraudTypeQueryService(new FraudTypeCatalog(List.of())).findAll());
    }
}
