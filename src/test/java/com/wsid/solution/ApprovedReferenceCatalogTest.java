package com.wsid.solution;
import static org.junit.jupiter.api.Assertions.*;
import com.wsid.ai.dto.SolutionCandidateReference;
import com.wsid.solution.content.ApprovedReferenceCatalog;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
class ApprovedReferenceCatalogTest {
    String entry="{\"code\":\"GUIDE\",\"version\":\"1\",\"title\":\"검토 제목\",\"excerpt\":\"검토 문단\",\"source\":\"https://example.org/source\",\"checkedAt\":\"2026-11-18\",\"reviewed\":true}";
    ApprovedReferenceCatalog load(String text) {return new ApprovedReferenceCatalog(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));}
    @Test void onlyReviewedExactVersionMatches() {
        var c=load("["+entry+"]");assertEquals(1,c.approved(List.of(new SolutionCandidateReference("GUIDE","1"))).size());
        assertTrue(c.approved(List.of(new SolutionCandidateReference("GUIDE","2"))).isEmpty());
        assertTrue(c.approved(List.of(new SolutionCandidateReference("OTHER","1"))).isEmpty());
    }
    @Test void unreviewedAndDuplicateRejectStartup() {
        assertThrows(IllegalArgumentException.class,()->load("["+entry.replace("true","false")+"]"));
        assertThrows(IllegalArgumentException.class,()->load("["+entry+","+entry+"]"));
    }
}
