package com.wsid.training.domain;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DiscoveryRulesTest {
    static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    static final TrainingRules RULES = new TrainingRules(12, 15, Duration.ofHours(24), 3, 5, 3, List.of("activity", "role"));
    @Test void minimumAnswersAndReadyStateSurviveSimulation() {
        var s = TrainingSession.start(1, RULES, NOW);
        for (int i=1;i<=2;i++) {
            var out = s.applyDiscoveryAnswer(new DiscoveryInput(true, true, true), RULES, NOW);
            assertEquals(DiscoveryStatus.ASKING, out.status());assertEquals(i, out.questionCount());
        }
        var out=s.applyDiscoveryAnswer(new DiscoveryInput(true,true,true), RULES, NOW);
        assertEquals(DiscoveryStatus.READY,out.status());assertEquals(2,out.questionCount());
        assertThrows(IllegalStateException.class,()->s.applyDiscoveryAnswer(new DiscoveryInput(true,true,true),RULES,NOW));
        s.beginSimulation("USED_GOODS_TRADE",RULES,NOW);
        assertEquals(3,s.getDiscoveryTurnCount());
        s.applyTurn(new TurnInput(UserAction.ASK_QUESTION,true,true),RULES,NOW);
        assertTrue(s.isKeySceneShown());assertEquals(DiscoveryStatus.READY,s.discoveryStatus());
    }
    @Test void hardLimitWinsEvenWhenAiFails() {
        for (boolean hasFacts : new boolean[]{true,false}) {
            var s=TrainingSession.start(1,RULES,NOW);
            for(int i=1;i<=5;i++) assertEquals(DiscoveryStatus.ASKING,s.applyDiscoveryAnswer(new DiscoveryInput(false,false,hasFacts),RULES,NOW).status());
            var out=s.applyDiscoveryAnswer(new DiscoveryInput(false,false,hasFacts),RULES,NOW);
            assertEquals(hasFacts?DiscoveryStatus.READY:DiscoveryStatus.DEFAULT_SCENARIO,out.status());
            assertEquals(5,out.questionCount());assertEquals(6,s.getDiscoveryTurnCount());
        }
    }
    @Test void enoughSuggestionDoesNotOverrideMissingFacts() {
        var s=TrainingSession.start(1,RULES,NOW);
        for(int i=0;i<5;i++) assertEquals(DiscoveryStatus.ASKING,s.applyDiscoveryAnswer(new DiscoveryInput(true,true,false),RULES,NOW).status());
    }
    @ParameterizedTest @ValueSource(ints={0,7}) void invalidMinimum(int min) {
        assertThrows(IllegalArgumentException.class,()->new TrainingRules(12,15,Duration.ofHours(24),3,5,min,List.of("role")));
    }
    @Test void invalidLimitsAndKeys() {
        assertThrows(IllegalArgumentException.class,()->new TrainingRules(12,15,Duration.ofHours(24),6,5,3,List.of("role")));
        assertThrows(IllegalArgumentException.class,()->new TrainingRules(12,15,Duration.ofHours(24),0,5,3,List.of("role")));
        assertThrows(IllegalArgumentException.class,()->new TrainingRules(12,15,Duration.ofHours(24),3,5,3,List.of("unsupported")));
    }
    @Test void factsFilterAndCorrection() {
        var facts=ContextFacts.fromJson("{\"activity\":\" 중고거래 \"}");
        var merged=facts.merge(Map.of("activity","택배","role","판매자","unknown","value","item",List.of("nested"),"channel","x".repeat(101),"counterpart"," "));
        assertEquals(Map.of("activity","택배","role","판매자"),merged.values());
        assertEquals(merged,ContextFacts.fromJson(merged.toJson()));assertTrue(merged.hasAll(List.of("activity","role")));
        assertEquals(ContextFacts.empty(),ContextFacts.fromJson(null));
        assertThrows(RuntimeException.class,()->ContextFacts.fromJson("{"));
        assertThrows(RuntimeException.class,()->ContextFacts.fromJson("[]"));
        assertThrows(UnsupportedOperationException.class,()->merged.values().put("role","changed"));
    }
    @Test void stateCompatibilityAndBothDirections() {
        for(String json: new String[]{"{}","{\"extra\":1}","null"}) {
            if (json.equals("null")) continue;
            assertEquals(FlowState.initial(),FlowState.fromJson(json));
        }
        var f=new FlowState(true,DiscoveryStatus.READY);
        assertEquals(f,FlowState.fromJson(f.toJson()));
        assertEquals(DiscoveryStatus.READY,new FlowState(false,DiscoveryStatus.READY).withKeySceneShown().discoveryStatus());
        assertTrue(new FlowState(true).withDiscoveryStatus(DiscoveryStatus.DEFAULT_SCENARIO).keySceneShown());
        assertThrows(RuntimeException.class,()->FlowState.fromJson("{"));
    }
}
