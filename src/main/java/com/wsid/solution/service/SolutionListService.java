package com.wsid.solution.service;

import com.wsid.auth.CurrentUser;
import com.wsid.common.error.*;
import com.wsid.solution.domain.*;
import com.wsid.solution.repository.SolutionSessionRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SolutionListService {
    private final CurrentUser user;
    private final SolutionSessionRepository sessions;
    private final ObjectMapper json = new ObjectMapper();
    public SolutionListService(CurrentUser user, SolutionSessionRepository sessions) {this.user=user;this.sessions=sessions;}
    @Transactional(readOnly=true)
    public Page completed(String rawSize, String rawCursor) {
        long userId=user.id();
        int size=10;
        if(rawSize!=null) {
            if(!rawSize.matches("[1-9][0-9]?") ) throw new ApiException(ErrorCode.INVALID_REQUEST);
            size=Integer.parseInt(rawSize);
            if(size>50) throw new ApiException(ErrorCode.INVALID_REQUEST);
        }
        var before=rawCursor==null ? new SolutionCursor(Instant.EPOCH,1) : SolutionCursor.decode(rawCursor);
        var rows=sessions.findCompletedPage(userId,rawCursor==null,before.completedAt(),before.id(),PageRequest.of(0,size+1));
        var items=rows.stream().limit(size).map(row -> {
            if(row.getTitle()!=null) return new Item(row.getId(),"COMPLETED",row.getTitle(),row.getCreatedAt(),row.getCompletedAt());
            var node=json.readTree(row.getFactsJson());
            if(!node.isObject()) throw new IllegalStateException("저장된 상담 사실 형식이 올바르지 않아요.");
            var facts=new HashMap<String,String>();
            for(String key:node.propertyNames()) {
                if(!node.get(key).isString()) throw new IllegalStateException("저장된 상담 사실 형식이 올바르지 않아요.");
                facts.put(key,node.get(key).asString());
            }
            return new Item(row.getId(),"COMPLETED",SolutionTitles.of(SolutionFacts.fromStrings(facts)),row.getCreatedAt(),row.getCompletedAt());
        }).toList();
        boolean hasNext=rows.size()>size;
        String next=hasNext?new SolutionCursor(items.getLast().completedAt(),items.getLast().solutionId()).encode():null;
        return new Page(items,next,hasNext);
    }
    public record Item(long solutionId,String status,String title,Instant createdAt,Instant completedAt) {}
    public record Page(List<Item> items,String nextCursor,boolean hasNext) {}
}
