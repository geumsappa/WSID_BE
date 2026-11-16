package com.wsid.content.controller;

import com.wsid.common.api.ApiResponse;
import com.wsid.content.dto.FraudTypeSummaryResponse;
import com.wsid.content.service.FraudTypeQueryService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/guides")
public class GuideController {
    private final FraudTypeQueryService service;

    public GuideController(FraudTypeQueryService service) {
        this.service = service;
    }

    @GetMapping("/fraud-types")
    public ApiResponse<List<FraudTypeSummaryResponse>> fraudTypes() {
        return ApiResponse.ok(service.findAll());
    }
}
