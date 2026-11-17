package com.wsid.solution.controller;

import com.wsid.common.api.ApiResponse;
import com.wsid.common.error.ApiException;
import com.wsid.common.error.ErrorCode;
import com.wsid.solution.dto.SolutionStateResponse;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.security.SolutionOwnerResolver;
import com.wsid.solution.service.SolutionQueryService;
import com.wsid.solution.service.StartSolutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 피해 대응 상담 API. 회원·비회원 모두 쓰고, 변경 요청은 CSRF 토큰이 필요해요(SolutionSecurityConfig). */
@RestController
@RequestMapping("/api/solutions")
public class SolutionController {
    private static final Pattern POSITIVE_ID = Pattern.compile("[1-9][0-9]{0,18}");

    private final StartSolutionService startService;
    private final SolutionQueryService queryService;
    private final SolutionOwnerResolver owners;

    public SolutionController(StartSolutionService startService, SolutionQueryService queryService,
            SolutionOwnerResolver owners) {
        this.startService = startService;
        this.queryService = queryService;
        this.owners = owners;
    }

    /**
     * 상담용 CSRF 토큰. 비회원도 받을 수 있고, 이때 Redis 세션과 SESSION 쿠키가 생겨요.
     * 응답 형식은 GET /api/auth/csrf(회원 전용)와 같고 토큰 저장소도 같아서 어느 쪽 토큰이든 상담 요청에 쓸 수 있어요.
     */
    @GetMapping("/csrf")
    public ApiResponse<CsrfInfo> csrf(@RequestAttribute(name = "_csrf") CsrfToken token, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(new CsrfInfo(token.getHeaderName(), token.getToken()));
    }

    /**
     * SOL-01. 본문은 선택이에요. Content-Type 없는 빈 요청은 선택형 시작이고,
     * 본문이 있으면 application/json만 받아요(그 외 415).
     */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StartSolutionResponse> start(@RequestBody(required = false) JsonNode body,
            Authentication authentication, HttpServletRequest request) {
        return ApiResponse.ok(startService.start(StartSolutionRequest.from(body),
                () -> owners.resolve(authentication, request)));
    }

    /** SOL-02. id 형식이 틀려도(문자·0·음수·범위 초과) 같은 404로 응답해 존재 여부를 드러내지 않아요. */
    @GetMapping("/sessions/{id}")
    public ApiResponse<SolutionStateResponse> get(@PathVariable("id") String id,
            Authentication authentication, HttpServletRequest request) {
        long solutionId = parseId(id);
        return ApiResponse.ok(queryService.get(solutionId, () -> owners.findExisting(authentication, request)));
    }

    private static long parseId(String raw) {
        if (raw == null || !POSITIVE_ID.matcher(raw).matches()) throw new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiException(ErrorCode.SOLUTION_NOT_FOUND);
        }
    }

    public record CsrfInfo(String headerName, String token) {}
}
