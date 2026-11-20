package com.wsid.solution.dto;

import com.wsid.solution.domain.SolutionStatus;

/** SOL-05: 최초·반복 종료 모두 같은 완료 상태만 반환해요. */
public record CompleteSolutionResponse(long solutionId, SolutionStatus status) {}
