package com.wsid.report.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.wsid.common.error.ApiException;
import com.wsid.report.domain.*;
import com.wsid.report.repository.TrainingReportRepository;
import com.wsid.training.domain.TrainingSession;
import com.wsid.training.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class ReportSourcesTest {
    private final ObjectMapper json = new ObjectMapper();
    static final String URL = "https://www.korea.kr/news/policyNewsView.do?newsId=148910622";

    static String scenario() {
        return """
            {"generationEvidence":{"retrievedBeforeGeneration":[{"caseId":"RC-001","sourceUrl":"%s"}],
             "usedForGeneration":[{"caseId":"RC-001","source":{"title":"가족 사칭 피해 예방 자료","publisher":"자료 발행기관",
             "author":"작성자","publishedDate":"2025-05-22","url":"%s", "license":{"code":"KOGL-1","scope":"TEXT_ONLY","evidenceUrl":"%s"}}}]},
             "basedOnCases":[{"caseId":"UNUSED","sourceUrl":"https://example.com"}]}
            """.formatted(URL, URL, URL);
    }

    @Test void onlyActualGenerationSourcesAreCredited() {
        var sources = ReportSources.fromScenario(json.readTree(scenario()));
        assertEquals(1, sources.size());
        assertEquals(URL, sources.getFirst().sourceUrl());
        assertEquals("작성자", sources.getFirst().author());
        assertEquals("KOGL-1", sources.getFirst().license());
    }

    @Test void legacyScenariosHaveNoInventedSources() {
        assertTrue(ReportSources.fromScenario(json.readTree("{\"basedOnCases\":[{\"title\":\"사후 매칭\"}]}" )).isEmpty());
        assertTrue(ReportSources.fromScenario(null).isEmpty());
    }

    @Test void duplicateUsesOfOneDocumentAreDisplayedOnce() {
        var tree = json.readTree(scenario());
        var uses = (tools.jackson.databind.node.ArrayNode) tree.path("generationEvidence").path("usedForGeneration");
        uses.add(uses.get(0).deepCopy());
        assertEquals(1, ReportSources.fromScenario(tree).size());
    }

    @Test void unsafeRestrictedOrUnretrievedSourcesAreRejected() {
        for (String body : List.of(scenario().replace("KOGL-1", "KOGL-4"),
                scenario().replace(URL, "javascript:alert(1)"),
                scenario().replace(URL, "https://example.com/source"),
                scenario().replace("\"retrievedBeforeGeneration\"", "\"notRetrieved\""))) {
            assertThrows(ApiException.class, () -> ReportSources.fromScenario(json.readTree(body)));
        }
    }

    @Test void creditsAreSnapshottedIntoReportAndSurviveScenarioDeletion() {
        var sessions = mock(TrainingSessionRepository.class);
        var reports = mock(TrainingReportRepository.class);
        var saved = mock(TrainingReport.class);
        var stored = new AtomicReference<TrainingReport>();
        when(reports.save(any())).thenAnswer(call -> { stored.set(call.getArgument(0)); return saved; });
        when(saved.getId()).thenReturn(7L);
        when(saved.getTitle()).thenReturn("훈련 결과");
        when(saved.isSaved()).thenReturn(true);
        when(saved.getReportJson()).thenAnswer(call -> stored.get().getReportJson());
        when(reports.findOwned(7L, 2L)).thenReturn(Optional.of(saved));
        var training = mock(TrainingSession.class);
        when(training.getId()).thenReturn(1L);
        when(training.getFraudTypeCode()).thenReturn("SMISHING");
        when(training.getScenarioJson()).thenReturn(scenario());
        var service = new ReportService(() -> 2L, sessions, mock(TrainingMessageRepository.class), reports, json);
        var fabricated = new ReportSource("평가 모델이 만든 출처", "가짜", null, null, "https://example.com", "KOGL-1");
        var evaluation = new ReportEvaluation("평가", List.of(), List.of(), List.of(),
                new ReportEvaluation.MockPageEvaluation(false, false, false), List.of(), List.of(fabricated));
        assertEquals(7L, service.recordEvaluation(training, evaluation, Instant.now()));
        when(training.getScenarioJson()).thenReturn(null);
        var detail = service.detail(7L);
        assertEquals(URL, detail.evaluation().sources().getFirst().sourceUrl());
        assertFalse(stored.get().getReportJson().contains("평가 모델이 만든 출처"));
    }

    @Test void oldReportJsonStillDeserializes() {
        var evaluation = json.readValue("""
            {"overallFeedback":"이전 평가","strengths":[],"improvements":[],"turnFeedback":[],
             "mockPageEvaluation":{"openedPage":false,"attemptedMockLogin":false,"checkedOfficialChannel":false},"recommendations":[]}
            """, ReportEvaluation.class);
        assertTrue(evaluation.sources().isEmpty());
    }
}
