package com.wsid.training.service;

import com.wsid.training.domain.TrainingRules;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wsid.training")
public record TrainingProperties(int keySceneDeadlineTurn, int maxSimulationTurns, Duration sessionTtl,
        int discoveryBaseQuestions, int discoveryMaxQuestions, int discoveryMinAnswers,
        List<String> discoveryRequiredFacts) {
    public TrainingRules toRules() {
        return new TrainingRules(keySceneDeadlineTurn, maxSimulationTurns, sessionTtl,
                discoveryBaseQuestions, discoveryMaxQuestions, discoveryMinAnswers, discoveryRequiredFacts);
    }
}
