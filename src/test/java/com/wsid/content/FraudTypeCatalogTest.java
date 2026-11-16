package com.wsid.content;

import static org.junit.jupiter.api.Assertions.*;

import com.wsid.content.catalog.FraudTypeCatalog;
import com.wsid.content.model.FraudType;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudTypeCatalogTest {
    @Test
    void filtersReviewStatusAndOrdersByDisplayOrderThenCode() {
        var catalog = new FraudTypeCatalog(List.of(type("Z", 2, true), type("B", 1, true),
                type("A", 1, true), type("DRAFT", 0, false)));
        assertEquals(List.of("A", "B", "Z"), catalog.findAllReviewed().stream().map(FraudType::code).toList());
    }

    @Test
    void snapshotIsImmutableAndIndependentOfInputList() {
        var input = new ArrayList<>(List.of(type("A", 0, true)));
        var catalog = new FraudTypeCatalog(input);
        input.clear();
        assertEquals(1, catalog.findAllReviewed().size());
        assertSame(catalog.findAllReviewed(), catalog.findAllReviewed());
        assertThrows(UnsupportedOperationException.class, () -> catalog.findAllReviewed().clear());
    }

    @Test
    void emptyAndAllUnreviewedCatalogsAreValid() {
        assertTrue(new FraudTypeCatalog(List.of()).findAllReviewed().isEmpty());
        assertTrue(new FraudTypeCatalog(List.of(type("DRAFT", 0, false))).findAllReviewed().isEmpty());
    }

    @Test
    void rejectsNullEntriesAndDuplicateCodesIncludingUnreviewedOnes() {
        assertThrows(IllegalArgumentException.class, () -> new FraudTypeCatalog(null));
        assertThrows(IllegalArgumentException.class, () -> new FraudTypeCatalog(Arrays.asList((FraudType) null)));
        assertThrows(IllegalArgumentException.class,
                () -> new FraudTypeCatalog(List.of(type("A", 0, true), type("A", 1, false))));
    }

    @Test
    void modelRejectsInvalidProgrammaticInputsAndNormalizesNames() {
        assertThrows(IllegalArgumentException.class, () -> type("invalid", 0, false));
        assertThrows(IllegalArgumentException.class, () -> type("A", -1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new FraudType("A", null, "내용", 0, true, URI.create("https://example.invalid"), LocalDate.of(2026, 11, 16)));
        assertThrows(IllegalArgumentException.class,
                () -> new FraudType("A", "제목", " ", 0, true, URI.create("https://example.invalid"), LocalDate.of(2026, 11, 16)));
        assertThrows(IllegalArgumentException.class,
                () -> new FraudType("A", "제목", "내용", 0, true, null, LocalDate.of(2026, 11, 16)));
        assertThrows(IllegalArgumentException.class,
                () -> new FraudType("A", "제목", "내용", 0, true, URI.create("https://example.invalid"), null));
        assertEquals("제목", type("A", 0, true).name());
    }

    static FraudType type(String code, int order, boolean reviewed) {
        return new FraudType(code, "  제목  ", " 설명 ", order, reviewed,
                URI.create("https://example.invalid/type"), LocalDate.of(2026, 11, 16));
    }
}
