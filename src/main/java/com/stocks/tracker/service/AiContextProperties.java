package com.stocks.tracker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Token limits for prompts sent to the model. The window is the model's context size; part of it
 * is reserved for the reply, and the market-data brief is additionally capped so a local model
 * isn't handed a prompt so large that it takes forever to read.
 */
@Component
public class AiContextProperties {

    private final int windowTokens;
    private final int outputReserveTokens;
    private final int briefMaxTokens;

    public AiContextProperties(@Value("${ai.context.window-tokens:200000}") int windowTokens,
                               @Value("${ai.context.output-reserve-tokens:16000}") int outputReserveTokens,
                               @Value("${ai.context.brief-max-tokens:60000}") int briefMaxTokens) {
        this.windowTokens = windowTokens;
        this.outputReserveTokens = outputReserveTokens;
        this.briefMaxTokens = briefMaxTokens;
    }

    public int getWindowTokens() {
        return windowTokens;
    }

    public int getOutputReserveTokens() {
        return outputReserveTokens;
    }

    public int getBriefMaxTokens() {
        return briefMaxTokens;
    }

    /** Tokens available for input: the window minus the room kept for the reply. */
    public int getInputBudgetTokens() {
        return Math.max(0, windowTokens - outputReserveTokens);
    }
}
