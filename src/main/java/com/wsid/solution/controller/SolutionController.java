package com.wsid.solution.controller;

import com.wsid.common.api.ApiResponse;
import com.wsid.solution.dto.StartSolutionRequest;
import com.wsid.solution.dto.StartSolutionResponse;
import com.wsid.solution.security.SolutionOwnerResolver;
import com.wsid.solution.service.StartSolutionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/** 피해 대응 상담 API. 회원·비회원 모두 쓰고, 변경 요청은 CSRF 토큰이 필요해요. */
@RestController
@RequestMapping("/api/solutions")
public class SolutionController {
    private final StartSolutionService startService;
    private final SolutionOwnerResolver owners;

    public SolutionController(StartSolutionService startService, SolutionOwnerResolver owners) {
        this.startService = startService;
        this.owners = owners;
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
}
