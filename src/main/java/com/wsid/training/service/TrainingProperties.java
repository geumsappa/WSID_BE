package com.wsid.training.service;

import com.wsid.training.domain.TrainingRules;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "wsid.training")
public record TrainingProperties(int keySceneDeadlineTurn, int maxSimulationTurns, Duration sessionTtl) {

    public TrainingRules toRules() {
        return new TrainingRules(keySceneDeadlineTurn, maxSimulationTurns, sessionTtl);
    }
}
