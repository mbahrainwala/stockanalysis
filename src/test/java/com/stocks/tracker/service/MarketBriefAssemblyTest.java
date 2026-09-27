package com.stocks.tracker.service;

import com.stocks.tracker.service.MarketBriefService.Section;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketBriefAssemblyTest {

    private static Section section(String title, int lines) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < lines; i++) {
            l.add("- " + title + " line " + i + " " + "x".repeat(90));
        }
        return new Section(title, "intro", l);
    }

    @Test
    void everythingFitsWhenTheBudgetIsLarge() {
        String out = MarketBriefService.assemble(List.of(section("A", 3), section("B", 3)), 10_000);
        assertTrue(out.contains("## A") && out.contains("## B"));
        assertFalse(out.contains("omitted"));
    }

    @Test
    void staysWithinBudgetAndDropsLowerPriorityFirst() {
        List<Section> sections = List.of(section("First", 20), section("Second", 20), section("Third", 20));
        int budget = 900;
        String out = MarketBriefService.assemble(sections, budget);
        assertTrue(TokenEstimator.estimate(out) <= budget, "brief must fit the budget, was " + TokenEstimator.estimate(out));
        assertTrue(out.contains("## First"));
        assertFalse(out.contains("## Third"), "lowest priority section should be dropped");
        assertTrue(out.contains("omitted"), "the model must be told data was left out");
    }

    @Test
    void emptySectionsAreSkippedAndTinyBudgetsYieldNoSections() {
        assertEquals("", MarketBriefService.assemble(List.of(new Section("Empty", "intro", List.of())), 5_000));
        String out = MarketBriefService.assemble(List.of(section("Big", 5)), 50);
        assertFalse(out.contains("## Big"));
    }
}
