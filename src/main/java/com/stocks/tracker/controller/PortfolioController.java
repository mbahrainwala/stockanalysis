package com.stocks.tracker.controller;

import com.stocks.tracker.dto.QuoteResult;
import com.stocks.tracker.model.Market;
import com.stocks.tracker.repository.TradingAccountRepository;
import com.stocks.tracker.security.CurrentUserService;
import com.stocks.tracker.service.AnalysisJobService;
import com.stocks.tracker.service.BrandingService;
import com.stocks.tracker.service.LlmService;
import com.stocks.tracker.service.PortfolioService;
import com.stocks.tracker.service.SpeculationChatService;
import com.stocks.tracker.service.SpeculationService;
import com.stocks.tracker.service.StockLookupException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

@Controller
public class PortfolioController {

    private final PortfolioService portfolioService;
    private final TradingAccountRepository accountRepository;
    private final LlmService llmService;
    private final AnalysisJobService analysisJobService;
    private final com.stocks.tracker.repository.SavedAnalysisRepository savedAnalysisRepository;
    private final SpeculationService speculationService;
    private final SpeculationChatService speculationChatService;
    private final com.stocks.tracker.service.ExternalRatingService ratingService;
    private final com.stocks.tracker.service.MarketBriefService briefService;
    private final CurrentUserService currentUser;
    private final BrandingService brandingService;
    private final com.stocks.tracker.repository.UserRepository userRepository;

    public PortfolioController(PortfolioService portfolioService, TradingAccountRepository accountRepository,
                               LlmService llmService, AnalysisJobService analysisJobService,
                               com.stocks.tracker.repository.SavedAnalysisRepository savedAnalysisRepository,
                               SpeculationService speculationService,
                               SpeculationChatService speculationChatService,
                               com.stocks.tracker.service.ExternalRatingService ratingService,
                               com.stocks.tracker.service.MarketBriefService briefService,
                               CurrentUserService currentUser,
                               BrandingService brandingService,
                               com.stocks.tracker.repository.UserRepository userRepository) {
        this.briefService = briefService;
        this.ratingService = ratingService;
        this.speculationChatService = speculationChatService;
        this.speculationService = speculationService;
        this.savedAnalysisRepository = savedAnalysisRepository;
        this.analysisJobService = analysisJobService;
        this.portfolioService = portfolioService;
        this.llmService = llmService;
        this.accountRepository = accountRepository;
        this.currentUser = currentUser;
        this.brandingService = brandingService;
        this.userRepository = userRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        var user = currentUser.currentUser();
        boolean isAdmin = user.getRole() == com.stocks.tracker.model.User.Role.ADMIN;
        model.addAttribute("portfolio", portfolioService.buildPortfolioView());
        model.addAttribute("accounts", accountRepository.findAllByOwnerId(user.getId()));
        model.addAttribute("markets", Market.values());
        model.addAttribute("speculation", speculationService.listRows());
        model.addAttribute("currencies", com.stocks.tracker.model.Currencies.SUPPORTED);
        model.addAttribute("username", user.getUsername());
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("companyName", brandingService.companyName());
        if (isAdmin) {
            model.addAttribute("users", userRepository.findAllByOrderByUsernameAsc());
        }
        return "index";
    }

    @PostMapping("/accounts")
    public String createAccount(@RequestParam String name,
                                 @RequestParam(required = false) String broker,
                                 @RequestParam(required = false) String currency,
                                 RedirectAttributes redirectAttributes) {
        try {
            portfolioService.createAccount(name, broker, currency);
            redirectAttributes.addFlashAttribute("success", "Account \"" + name + "\" created.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not create account: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/accounts/{id}/update")
    public String updateAccount(@PathVariable Long id,
                                @RequestParam String name,
                                @RequestParam(required = false) String broker,
                                @RequestParam(required = false) String currency,
                                RedirectAttributes redirectAttributes) {
        try {
            portfolioService.updateAccount(id, name, broker, currency);
            redirectAttributes.addFlashAttribute("success", "Account updated.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not update account: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/accounts/{id}/delete")
    public String deleteAccount(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            String name = portfolioService.deleteAccount(id);
            redirectAttributes.addFlashAttribute("success", "Account \"" + name + "\" and all its holdings were deleted.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not delete account: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/holdings")
    public String addShares(@RequestParam Long accountId,
                             @RequestParam String symbol,
                             @RequestParam Market market,
                             @RequestParam BigDecimal shares,
                             @RequestParam(required = false) BigDecimal pricePaid,
                             RedirectAttributes redirectAttributes) {
        try {
            portfolioService.addShares(accountId, symbol, market, shares, pricePaid);
            redirectAttributes.addFlashAttribute("success",
                    "Added " + shares + " shares of " + symbol.toUpperCase() + " (" + market.getLabel() + ").");
        } catch (StockLookupException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not add shares: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/holdings/set")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> setShares(@RequestParam Long accountId,
                                                                     @RequestParam Long stockId,
                                                                     @RequestParam(required = false) String shares) {
        try {
            String text = shares == null ? "" : shares.trim().replace(",", "");
            portfolioService.setShares(accountId, stockId,
                    text.isEmpty() || text.equals("-") ? BigDecimal.ZERO : new BigDecimal(text));
            return org.springframework.http.ResponseEntity.noContent().build();
        } catch (NumberFormatException e) {
            return org.springframework.http.ResponseEntity.badRequest().body("Enter a valid number.");
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/holdings/cost")
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> setAverageCost(@RequestParam Long accountId,
                                                                          @RequestParam Long stockId,
                                                                          @RequestParam(required = false) String cost) {
        try {
            String text = cost == null ? "" : cost.trim().replace(",", "");
            portfolioService.setAverageCost(accountId, stockId, text.isEmpty() ? null : new BigDecimal(text));
            return org.springframework.http.ResponseEntity.noContent().build();
        } catch (NumberFormatException e) {
            return org.springframework.http.ResponseEntity.badRequest().body("Enter a valid number.");
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ---- Speculation list ----

    @PostMapping("/speculation")
    public String addSpeculation(@RequestParam String symbol,
                                 @RequestParam Market market,
                                 @RequestParam(required = false) String note,
                                 RedirectAttributes redirectAttributes) {
        try {
            var entry = speculationService.add(symbol, market, note, com.stocks.tracker.model.SpeculationEntry.Source.USER);
            redirectAttributes.addFlashAttribute("success", "Added " + entry.getStock().getSymbol() + " to Speculation.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/speculation/{id}/delete")
    public String deleteSpeculation(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        try {
            speculationService.delete(id);
            redirectAttributes.addFlashAttribute("success", "Removed from Speculation.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/";
    }

    /**
     * Starts a background answer to the latest question in a conversation about the speculation stocks.
     * Poll and cancel it through the /api/ai/analyze/{id} endpoints.
     */
    @PostMapping("/api/speculation/chat")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> speculationChat(
            @RequestBody java.util.List<java.util.Map<String, String>> messages) {
        java.util.List<LlmService.Message> conversation = new java.util.ArrayList<>();
        for (var m : messages) {
            String role = m.get("role");
            String content = m.get("content") == null ? "" : m.get("content").strip();
            if (!"user".equals(role) && !"assistant".equals(role) || content.isEmpty()) {
                return org.springframework.http.ResponseEntity.badRequest().body("Invalid conversation.");
            }
            conversation.add(new LlmService.Message(role, content.length() > 6000 ? content.substring(0, 6000) : content));
        }
        if (conversation.isEmpty() || !"user".equals(conversation.get(conversation.size() - 1).role())) {
            return org.springframework.http.ResponseEntity.badRequest().body("Ask a question first.");
        }
        // Keep the prompt bounded: only the most recent turns are sent.
        if (conversation.size() > 12) {
            conversation = new java.util.ArrayList<>(conversation.subList(conversation.size() - 12, conversation.size()));
            while (!conversation.isEmpty() && !"user".equals(conversation.get(0).role())) {
                conversation.remove(0);
            }
        }
        final var turns = conversation;
        var job = analysisJobService.startExclusive(AnalysisJobService.KIND_SPECULATION_CHAT,
                phase -> speculationChatService.ask(turns, phase));
        if (job == null) {
            return org.springframework.http.ResponseEntity.status(409).body("A reply is still being generated.");
        }
        return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("id", job.getId()));
    }

    /** Analyst consensus, recent analyst actions and news for a speculation stock, from external sources. */
    @GetMapping("/api/speculation/{id}/rating")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> speculationRating(@PathVariable Long id) {
        com.stocks.tracker.dto.SpeculationRow row;
        try {
            row = speculationService.findRow(id);
        } catch (IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.status(404).body(e.getMessage());
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("symbol", row.symbol());
        body.put("market", row.market().getLabel());
        body.put("companyName", row.companyName());
        body.put("currencySymbol", row.currencySymbol());
        body.put("currentPrice", row.currentPrice());
        body.put("addedPrice", row.addedPrice());
        body.put("ai", row.isAi());
        body.put("note", row.note());
        body.put("rating", ratingService.lookup(row.symbol(), row.market()));
        return org.springframework.http.ResponseEntity.ok(body);
    }

    @GetMapping("/api/speculation/{id}/history")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> speculationHistory(@PathVariable Long id,
                                                                         @RequestParam(defaultValue = "3mo") String range) {
        if (!com.stocks.tracker.service.MarketDataService.HISTORY_RANGES.contains(range)) {
            return org.springframework.http.ResponseEntity.badRequest().body("Unsupported range.");
        }
        try {
            return org.springframework.http.ResponseEntity.ok(speculationService.history(id, range));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(404).body(e.getMessage());
        }
    }

    /** The current user's portfolio as CSV, one column per trading account (share counts). */
    @GetMapping("/portfolio/export.csv")
    public org.springframework.http.ResponseEntity<byte[]> exportPortfolioCsv() {
        var view = portfolioService.buildPortfolioView();
        StringBuilder sb = new StringBuilder("Symbol,Market,Company,Price");
        for (var account : view.accounts()) {
            sb.append(',').append(csvField(account.getName()));
        }
        sb.append(",Total Shares,Total Value,Gain/Loss (USD)\r\n");
        for (var row : view.rows()) {
            sb.append(csvField(row.getSymbol())).append(',')
                    .append(csvField(row.getMarket().getLabel())).append(',')
                    .append(csvField(row.getCompanyName())).append(',')
                    .append(row.getCurrentPrice() == null ? "" : row.getCurrentPrice().toPlainString());
            for (var account : view.accounts()) {
                BigDecimal shares = row.getSharesByAccountId().get(account.getId());
                sb.append(',').append(shares == null ? "" : shares.stripTrailingZeros().toPlainString());
            }
            sb.append(',').append(row.getTotalShares().stripTrailingZeros().toPlainString())
                    .append(',').append(row.getTotalValue().toPlainString())
                    .append(',').append(row.getGainLossUsd() == null ? "" : row.getGainLossUsd().toPlainString())
                    .append("\r\n");
        }
        byte[] csv = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"portfolio.csv\"")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        return value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                ? '"' + value.replace("\"", "\"\"") + '"'
                : value;
    }

    @PostMapping("/prices/refresh")
    public String refreshPrices(RedirectAttributes redirectAttributes) {
        var running = analysisJobService.latest(AnalysisJobService.KIND_ANALYSIS);
        if (running != null && running.getState() == AnalysisJobService.State.RUNNING) {
            redirectAttributes.addFlashAttribute("error", "An analysis is running and refreshes prices itself; try again when it finishes.");
            return "redirect:/";
        }
        int updated = portfolioService.refreshAllPrices();
        redirectAttributes.addFlashAttribute("success", "Refreshed market prices for " + updated + " stock(s).");
        return "redirect:/";
    }

    @GetMapping("/api/stocks/lookup")
    @ResponseBody
    public QuoteResult lookupStock(@RequestParam String symbol, @RequestParam Market market) {
        return portfolioService.lookupSymbol(symbol, market);
    }

    @GetMapping("/api/stocks/search")
    @ResponseBody
    public java.util.List<com.stocks.tracker.dto.SymbolMatch> searchStocks(@RequestParam String query,
                                                                           @RequestParam Market market) {
        return portfolioService.searchSymbols(query, market);
    }

    // ---- AI / Ollama setup ----

    @GetMapping("/api/ai/config")
    @ResponseBody
    public LlmService.Config aiConfig() {
        return llmService.getConfig();
    }

    @PostMapping("/api/ai/config")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> saveAiConfig(@RequestParam String endpoint,
                                                                   @RequestParam(required = false) String provider,
                                                                   @RequestParam(required = false) String model) {
        try {
            return org.springframework.http.ResponseEntity.ok(llmService.saveConfig(provider, endpoint, model));
        } catch (IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    /** The signed-in user's own custom prompt, plus the default the Reset button restores. */
    @GetMapping("/api/ai/prompt")
    @ResponseBody
    public java.util.Map<String, String> aiPrompt() {
        return java.util.Map.of("customPrompt", llmService.getCustomPrompt(),
                "defaultPrompt", LlmService.DEFAULT_CUSTOM_PROMPT);
    }

    @PostMapping("/api/ai/prompt")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> saveAiPrompt(@RequestParam(required = false) String customPrompt) {
        try {
            return org.springframework.http.ResponseEntity.ok(java.util.Map.of("customPrompt", llmService.saveCustomPrompt(customPrompt)));
        } catch (IllegalArgumentException e) {
            return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/api/ai/status")
    @ResponseBody
    public LlmService.Status aiStatus(@RequestParam(required = false) String endpoint,
                                      @RequestParam(required = false) String provider) {
        return llmService.checkStatus(endpoint, provider);
    }

    @PostMapping("/api/ai/analyze")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> analyzePortfolio(@RequestParam(defaultValue = "false") boolean rejoinOnly) {
        if (rejoinOnly) {
            // Used after a page reload: reattach to a running analysis, but never start a new one.
            var running = analysisJobService.latest(AnalysisJobService.KIND_ANALYSIS);
            return running != null && running.getState() == AnalysisJobService.State.RUNNING
                    ? org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("id", running.getId()))
                    : org.springframework.http.ResponseEntity.status(409).body("No analysis is running.");
        }
        if (!portfolioService.hasData()) {
            return org.springframework.http.ResponseEntity.badRequest().body("Add some holdings or speculation stocks before analyzing.");
        }
        String instructions = "Today is " + java.time.LocalDate.now() + ". You have no tools or internet access for this request: "
                + "everything you need is provided below, so do not say you will fetch or look anything up. Write the complete analysis now.\n\n"
                + "Analyze the following investment portfolio. Cover diversification and concentration risk, "
                + "exposure by market and currency, notable gains and losses versus cost, and any suggestions worth "
                + "considering. Be specific and refer to holdings by symbol. Prices and market data were refreshed just now. "
                + "Base your analysis on the market snapshot, analyst views, new idea candidates and headlines provided after the "
                + "portfolio; if a note says some data was omitted, treat that data as incomplete.\n\n"
                + "If a Speculation watchlist is included, those are stocks the user is considering buying but does not own; "
                + "do not fold them into the portfolio's diversification, exposure or concentration figures above. "
                + "For each one, say whether it looks worth buying now, worth waiting on, or worth dropping, using how it has "
                + "moved since it was added. Also judge how earlier AI-added picks are doing.\n\n"
                + "If you have new stocks worth adding to the Speculation watchlist, end your reply with exactly one block in "
                + "this format and write nothing after it (omit the block if you have no ideas):\n"
                + "```speculation\n"
                + "[{\"symbol\": \"TICKER\", \"market\": \"US\", \"reason\": \"one sentence\"}]\n"
                + "```\n"
                + "Use at most " + SpeculationService.MAX_AI_PICKS + " picks. market must be one of US, CANADA_TSX, CANADA_TSXV, "
                + "INDIA_NSE, INDIA_BSE, and symbol is the bare ticker without an exchange suffix. Only suggest real, listed "
                + "tickers you are confident exist; each ticker is verified against live market data.\n\n";
        // Refresh everything first so the analysis works from current data, then build the prompt to fit the model's context.
        var job = analysisJobService.start(AnalysisJobService.KIND_ANALYSIS, phase -> {
            phase.accept("Refreshing market prices and exchange rates");
            portfolioService.refreshAllPrices();
            ratingService.invalidate();
            phase.accept("Preparing your portfolio data");
            String summary = portfolioService.buildPortfolioSummary();
            if (summary == null) {
                throw new IllegalStateException("Nothing to analyze: add holdings or speculation stocks first.");
            }
            String head = instructions + summary;
            phase.accept("Gathering analyst ratings, news and market data");
            String market = briefService.buildBrief(com.stocks.tracker.service.TokenEstimator.estimate(head), true);
            return market.isEmpty() ? head : head + "\n\n" + market;
        });
        return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("id", job.getId()));
    }

    // ---- Saved analyses ----

    @GetMapping("/api/ai/analyses")
    @ResponseBody
    public java.util.List<java.util.Map<String, Object>> savedAnalyses() {
        return savedAnalysisRepository.findAllByOwnerIdOrderByCreatedAtDesc(currentUser.currentUserId()).stream()
                .map(a -> java.util.Map.<String, Object>of(
                        "id", a.getId(),
                        "createdAt", a.getCreatedAt().toString(),
                        "model", a.getModel() == null ? "" : a.getModel(),
                        "durationMs", a.getDurationMs()))
                .toList();
    }

    @GetMapping("/api/ai/analyses/{id}")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> savedAnalysis(@PathVariable Long id) {
        return savedAnalysisRepository.findByIdAndOwnerId(id, currentUser.currentUserId())
                .<org.springframework.http.ResponseEntity<?>>map(a -> org.springframework.http.ResponseEntity.ok(java.util.Map.of(
                        "id", a.getId(),
                        "createdAt", a.getCreatedAt().toString(),
                        "model", a.getModel() == null ? "" : a.getModel(),
                        "durationMs", a.getDurationMs(),
                        "prompt", a.getPrompt(),
                        "response", a.getResponse())))
                .orElseGet(() -> org.springframework.http.ResponseEntity.status(404).body("Analysis not found."));
    }

    @PostMapping("/api/ai/analyses/{id}/delete")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> deleteSavedAnalysis(@PathVariable Long id) {
        if (!savedAnalysisRepository.existsByIdAndOwnerId(id, currentUser.currentUserId())) {
            return org.springframework.http.ResponseEntity.status(404).body("Analysis not found.");
        }
        savedAnalysisRepository.deleteById(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    @PostMapping("/api/ai/analyze/{id}/cancel")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> cancelAnalysis(@PathVariable String id) {
        return analysisJobService.cancel(id)
                ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.status(409).body("Nothing to cancel: the analysis has already finished.");
    }

    /** Progress of the most recent portfolio analysis, so the page can show it after the popup is closed. */
    @GetMapping("/api/ai/analyze/latest")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> latestAnalysis() {
        var job = analysisJobService.latest(AnalysisJobService.KIND_ANALYSIS);
        if (job == null) {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", job.getId());
        body.put("state", job.getState().name());
        body.put("phase", job.getPhase());
        body.put("elapsedMs", job.getElapsedMs());
        if (job.getError() != null) {
            body.put("error", job.getError());
        }
        return org.springframework.http.ResponseEntity.ok(body);
    }

    @GetMapping("/api/ai/analyze/{id}")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> analysisStatus(@PathVariable String id) {
        var job = analysisJobService.getOwned(id);
        if (job == null) {
            return org.springframework.http.ResponseEntity.status(404).body("Analysis not found (the app may have restarted).");
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("state", job.getState().name());
        body.put("elapsedMs", job.getElapsedMs());
        body.put("phase", job.getPhase());
        if (job.getResult() != null) {
            body.put("model", job.getResult().model());
            body.put("response", job.getResult().response());
        }
        if (job.getError() != null) {
            body.put("error", job.getError());
        }
        return org.springframework.http.ResponseEntity.ok(body);
    }

    /** Starts a test prompt as a background job; poll and cancel via the /api/ai/analyze/{id} endpoints. */
    @PostMapping("/api/ai/chat")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> aiChat(@RequestParam String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return org.springframework.http.ResponseEntity.badRequest().body("Prompt is required.");
        }
        var job = analysisJobService.start("chat", prompt);
        return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("id", job.getId()));
    }

    @ExceptionHandler(StockLookupException.class)
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> handleLookupException(StockLookupException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
    }
}
