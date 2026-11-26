package com.wsid.training.controller;

import com.wsid.auth.dto.AuthResponses.Success;
import com.wsid.training.dto.TrainingResponses;
import com.wsid.training.dto.TrainingRequests;
import com.wsid.training.service.TrainingService;
import com.wsid.training.service.TrainingWorkflow;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trainings")
public class TrainingsController {
    private final TrainingService training;
    private final TrainingWorkflow workflow;

    public TrainingsController(TrainingService training, TrainingWorkflow workflow) {
        this.training = training;
        this.workflow = workflow;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Success<TrainingResponses.Created> create(@RequestBody CreateRequest request) {
        return Success.of(training.create());
    }

    @GetMapping("/active")
    public Success<TrainingResponses.Active> active() {
        return Success.of(training.active().orElse(null));
    }

    @GetMapping("/{id}")
    public Success<TrainingResponses.Detail> detail(@PathVariable long id) {
        return Success.of(training.detail(id));
    }

    @PostMapping("/{id}/discovery/messages")
    public Success<TrainingResponses.Discovery> discovery(@PathVariable long id,
            @Valid @RequestBody TrainingRequests.Discovery request) {
        return Success.of(workflow.discover(id, request));
    }

    @PostMapping("/{id}/scenario")
    public Success<TrainingResponses.Scenario> scenario(@PathVariable long id,
            @Valid @RequestBody TrainingRequests.Versioned request) {
        return Success.of(workflow.scenario(id, request));
    }

    @PostMapping("/{id}/start")
    public Success<TrainingResponses.Started> start(@PathVariable long id,
            @Valid @RequestBody TrainingRequests.Versioned request) {
        return Success.of(workflow.start(id, request));
    }

    @PostMapping("/{id}/messages")
    public Success<TrainingResponses.Message> message(@PathVariable long id,
            @Valid @RequestBody TrainingRequests.Message request) {
        return Success.of(workflow.message(id, request));
    }

    @GetMapping("/{id}/mock-page")
    public Success<TrainingResponses.MockPage> mockPage(@PathVariable long id) {
        return Success.of(workflow.mockPage(id));
    }

    @PostMapping("/{id}/actions")
    public Success<TrainingResponses.Action> action(@PathVariable long id,
            @Valid @RequestBody TrainingRequests.Action request) {
        return Success.of(workflow.action(id, request));
    }

    @PostMapping("/{id}/finish")
    public Success<TrainingResponses.Finished> finish(@PathVariable long id,
            @Valid @RequestBody TrainingRequests.Finish request) {
        return Success.of(workflow.finish(id, request));
    }

    public record CreateRequest() {}
}
