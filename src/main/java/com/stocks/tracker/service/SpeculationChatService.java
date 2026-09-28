package com.stocks.tracker.service;

import java.time.LocalDate;
import java.util.List;

/** Answers questions about the user's Speculation stocks, grounding the model in current data. */
@org.springframework.stereotype.Service
public class SpeculationChatService {

    private final LlmService llm;
    private final PortfolioService portfolio;

    private final MarketBriefService brief;

    public SpeculationChatService(LlmService llm, PortfolioService portfolio, MarketBriefService brief) {
        this.llm = llm;
        this.portfolio = portfolio;
        this.brief = brief;
    }

    /** Builds fresh context (holdings, watchlist, market data) and asks the model; may take a while. */
    public LlmService.ChatResult ask(List<LlmService.Message> conversation, java.util.function.Consumer<String> phase) {
        phase.accept("Gathering market data");
        StringBuilder ctx = new StringBuilder();
        ctx.append("You are helping the user with the Speculation tab of their stock tracker. The Speculation watchlist "
                + "below is stocks the user is considering buying but does not own - treat those as speculation, never as "
                + "part of the portfolio. The user asks you questions about them and may ask for new ideas. Today is ")
                .append(LocalDate.now()).append(". Use the data below, which is current, and refer to stocks by symbol. "
                + "You have no tools or internet access: do not say you will look anything up, just answer. "
                + "If asked about a stock that is not in this data, say you have no live data for it and answer from general "
                + "knowledge with that caveat. Be concise and concrete; mention key risks. This is not financial advice.\n\n"
                + "If you have new stocks worth adding to the Speculation watchlist, end your reply with exactly one block "
                + "in this format and write nothing after it (omit the block entirely if you have no new ideas, and never "
                + "repeat a symbol already on the watchlist below):\n"
                + "```speculation\n"
                + "[{\"symbol\": \"TICKER\", \"market\": \"US\", \"reason\": \"one sentence\"}]\n"
                + "```\n"
                + "Use at most " + SpeculationService.MAX_AI_PICKS + " picks. market must be one of US, CANADA_TSX, "
                + "CANADA_TSXV, INDIA_NSE, INDIA_BSE, and symbol is the bare ticker without an exchange suffix. Only "
                + "suggest real, listed tickers you are confident exist; each ticker is verified against live market data.\n\n");
        String summary = portfolio.buildPortfolioSummary();
        ctx.append(summary == null ? "The user has no holdings or watchlist entries yet.\n" : summary);
        int used = TokenEstimator.estimate(ctx.toString());
        for (LlmService.Message m : conversation) {
            used += TokenEstimator.estimate(m.content());
        }
        String data = brief.buildBrief(used, false);
        if (!data.isEmpty()) {
            ctx.append('\n').append(data);
        }
        phase.accept("Waiting for the model");
        return llm.chat(conversation, ctx.toString(), null);
    }
}
