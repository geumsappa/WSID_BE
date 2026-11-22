package com.wsid.solution;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.wsid.auth.CurrentUser;
import com.wsid.common.error.ApiException;
import com.wsid.solution.domain.*;
import com.wsid.solution.repository.SolutionSessionRepository;
import com.wsid.solution.service.SolutionListService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SolutionListServiceTest {
    final CurrentUser user=mock(CurrentUser.class);
    final SolutionSessionRepository repo=mock(SolutionSessionRepository.class);
    final SolutionListService service=new SolutionListService(user,repo);
    @Test void titleUsesOnlyFirstTwoYesFacts() {
        assertEquals("송금·정보 제공 상담",SolutionTitles.of(SolutionFacts.fromStrings(SolutionDomainTest.facts("YES","YES","YES","YES"))));
        assertEquals("피해 대응 상담",SolutionTitles.of(SolutionFacts.unknown()));
    }
    @Test void descriptionTitleIsNormalizedAndCutAtFortyCharacters() {
        assertEquals("중고거래에서 사기가 의심돼요.",SolutionTitles.fromDescription("  중고거래에서\n\n사기가   의심돼요.  "));
        String forty="가".repeat(40);
        assertEquals(forty,SolutionTitles.fromDescription(forty));
        assertEquals(forty+"…",SolutionTitles.fromDescription(forty+"나"));
        assertEquals("😀".repeat(40)+"…",SolutionTitles.fromDescription("😀".repeat(41)));
        assertNull(SolutionTitles.fromDescription(" \n "));
        assertNull(SolutionTitles.fromDescription(null));
    }
    @Test void storedTitleIsUsedBeforeFactTitle() {
        when(user.id()).thenReturn(9L);
        var row=mock(SolutionSessionRepository.CompletedRow.class);
        when(row.getId()).thenReturn(8L);when(row.getTitle()).thenReturn("중고거래에서 사기가 의심돼요.");
        when(row.getCompletedAt()).thenReturn(Instant.parse("2026-11-19T05:20:41Z"));
        when(repo.findCompletedPage(anyLong(),anyBoolean(),any(),anyLong(),any())).thenReturn(List.of(row));
        assertEquals("중고거래에서 사기가 의심돼요.",service.completed(null,null).items().getFirst().title());
        verify(row,never()).getFactsJson();
    }
    @Test void cursorRoundTripAndMalformedInput() {
        var cursor=new SolutionCursor(Instant.parse("2026-11-19T05:20:41.123456Z"),238);
        assertEquals(cursor,SolutionCursor.decode(cursor.encode()));
        for(String invalid:List.of("", "@@", "MQ", "MTk3MC0wMS0wMVQwMDowMDowMFp8MA")) assertThrows(ApiException.class,()->SolutionCursor.decode(invalid));
    }
    @Test void pageBoundaryAndSizeValidation() {
        when(user.id()).thenReturn(9L);
        var row=mock(SolutionSessionRepository.CompletedRow.class);
        when(row.getId()).thenReturn(8L);when(row.getFactsJson()).thenReturn("{\"transferredMoney\":\"UNKNOWN\",\"sharedInformation\":\"UNKNOWN\",\"installedApp\":\"UNKNOWN\",\"contactContinues\":\"UNKNOWN\"}");
        when(row.getCompletedAt()).thenReturn(Instant.parse("2026-11-19T05:20:41Z"));
        when(repo.findCompletedPage(anyLong(),anyBoolean(),any(),anyLong(),any())).thenReturn(List.of(row,row));
        var page=service.completed("1",null);assertTrue(page.hasNext());assertEquals(1,page.items().size());assertNotNull(page.nextCursor());
        for(String invalid:List.of("0","51","-1","1.0","abc")) assertThrows(ApiException.class,()->service.completed(invalid,null));
    }
}
