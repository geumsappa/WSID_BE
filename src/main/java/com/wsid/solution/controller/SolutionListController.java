package com.wsid.solution.controller;
import com.wsid.common.api.ApiResponse;
import com.wsid.solution.service.SolutionListService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/solutions/sessions")
public class SolutionListController {
    private final SolutionListService service;
    public SolutionListController(SolutionListService service) {this.service=service;}
    @GetMapping
    public ApiResponse<SolutionListService.Page> list(@RequestParam(name="size",required=false) String size,
            @RequestParam(name="cursor",required=false) String cursor,HttpServletResponse response) {
        response.setHeader("Cache-Control","no-store");
        return ApiResponse.ok(service.completed(size,cursor));
    }
}
