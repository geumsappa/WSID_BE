package com.wsid.solution;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wsid.auth.CurrentUser;
import com.wsid.auth.domain.User;
import com.wsid.auth.repository.UserRepository;
import com.wsid.solution.domain.*;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
@Tag("db")
@SpringBootTest(properties="spring.datasource.url=${WSID_TEST_DB_URL:jdbc:postgresql://localhost:5432/wsid_ai01_test}")
class SolutionListDatabaseTest {
    @Autowired StartSolutionService starts;
    @Autowired CompleteSolutionService completes;
    @Autowired SolutionListService list;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentUser user;
    final List<Long> ids=new ArrayList<>(), usersToDelete=new ArrayList<>();
    long userId() {long id=users.saveAndFlush(User.localDev(UUID.randomUUID()+"@test.invalid",Instant.now())).getId();usersToDelete.add(id);return id;}
    long start(SolutionOwner owner) {long id=starts.start(StartSolutionRequest.empty(),()->owner).solutionId();ids.add(id);return id;}
    @AfterEach void cleanup() {for(long id:ids){jdbc.update("DELETE FROM solution_messages WHERE solution_id=?",id);jdbc.update("DELETE FROM solution_sessions WHERE id=?",id);}for(long id:usersToDelete)jdbc.update("DELETE FROM users WHERE id=?",id);}
    @Test void filtersOwnersAndPagesSameTimestamp() {
        long a=userId(),b=userId();when(user.id()).thenReturn(a);
        var own=SolutionOwner.member(a);var other=SolutionOwner.member(b);var anon=SolutionOwner.anonymous(UUID.randomUUID());
        long first=start(own),second=start(own),active=start(own),otherId=start(other),anonymousId=start(anon);
        for(long id:List.of(first,second)) completes.complete(id,()->Optional.of(own));
        completes.complete(otherId,()->Optional.of(other));completes.complete(anonymousId,()->Optional.of(anon));
        jdbc.update("UPDATE solution_sessions SET completed_at='2026-11-19 05:20:41+00' WHERE id IN (?,?)",first,second);
        var page=list.completed("1",null);assertEquals(second,page.items().getFirst().solutionId());assertTrue(page.hasNext());
        var next=list.completed("1",page.nextCursor());assertEquals(first,next.items().getFirst().solutionId());assertFalse(next.hasNext());assertNull(next.nextCursor());
        assertEquals("피해 대응 상담",next.items().getFirst().title());
        jdbc.update("UPDATE solution_sessions SET title='중고거래에서 사기가 의심돼요.' WHERE id=?",first);
        assertEquals("중고거래에서 사기가 의심돼요.",list.completed("1",page.nextCursor()).items().getFirst().title());
    }
}
