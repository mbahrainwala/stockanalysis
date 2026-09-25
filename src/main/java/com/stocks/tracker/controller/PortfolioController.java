package com.stocks.tracker.controller;

import com.stocks.tracker.dto.QuoteResult;
import com.stocks.tracker.model.Market;
import com.stocks.tracker.repository.TradingAccountRepository;
import com.stocks.tracker.service.AnalysisJobService;
import com.stocks.tracker.service.LlmService;
import com.stocks.tracker.service.PortfolioService;
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

    public PortfolioController(PortfolioService portfolioService, TradingAccountRepository accountRepository,
                               LlmService llmService, AnalysisJobService analysisJobService) {
        this.analysisJobService = analysisJobService;
        this.portfolioService = portfolioService;
        this.llmService = llmService;
        this.accountRepository = accountRepository;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("portfolio", portfolioService.buildPortfolioView());
        model.addAttribute("accounts", accountRepository.findAll());
        model.addAttribute("markets", Market.values());
        return "index";
    }

    @PostMapping("/accounts")
    public String createAccount(@RequestParam String name,
                                 @RequestParam(required = false) String broker,
                                 RedirectAttributes redirectAttributes) {
        try {
            portfolioService.createAccount(name, broker);
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
                                RedirectAttributes redirectAttributes) {
        try {
            portfolioService.updateAccount(id, name, broker);
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

    @PostMapping("/prices/refresh")
    public String refreshPrices(RedirectAttributes redirectAttributes) {
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

    @PostMapping("/api/ai/prompt")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> saveAiPrompt(@RequestParam(required = false) String customPrompt) {
        try {
            return org.springframework.http.ResponseEntity.ok(llmService.saveCustomPrompt(customPrompt));
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
    public org.springframework.http.ResponseEntity<?> analyzePortfolio() {
        String summary = portfolioService.buildPortfolioSummary();
        if (summary == null) {
            return org.springframework.http.ResponseEntity.badRequest().body("Add some holdings before analyzing.");
        }
        String request = "Analyze the following investment portfolio. Cover diversification and concentration risk, "
                + "exposure by market and currency, notable gains and losses versus cost, and any suggestions worth "
                + "considering. Be specific and refer to holdings by symbol. Prices are the last refreshed prices.\n\n"
                + summary;
        var job = analysisJobService.start(request);
        return org.springframework.http.ResponseEntity.accepted().body(java.util.Map.of("id", job.getId()));
    }

    @PostMapping("/api/ai/analyze/{id}/cancel")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> cancelAnalysis(@PathVariable String id) {
        return analysisJobService.cancel(id)
                ? org.springframework.http.ResponseEntity.noContent().build()
                : org.springframework.http.ResponseEntity.status(409).body("Nothing to cancel: the analysis has already finished.");
    }

    @GetMapping("/api/ai/analyze/{id}")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> analysisStatus(@PathVariable String id) {
        var job = analysisJobService.get(id);
        if (job == null) {
            return org.springframework.http.ResponseEntity.status(404).body("Analysis not found (the app may have restarted).");
        }
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("state", job.getState().name());
        body.put("elapsedMs", job.getElapsedMs());
        if (job.getResult() != null) {
            body.put("model", job.getResult().model());
            body.put("response", job.getResult().response());
        }
        if (job.getError() != null) {
            body.put("error", job.getError());
        }
        return org.springframework.http.ResponseEntity.ok(body);
    }

    @PostMapping("/api/ai/chat")
    @ResponseBody
    public org.springframework.http.ResponseEntity<?> aiChat(@RequestParam String prompt) {
        try {
            return org.springframework.http.ResponseEntity.ok(llmService.chat(prompt));
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @ExceptionHandler(StockLookupException.class)
    @ResponseBody
    public org.springframework.http.ResponseEntity<String> handleLookupException(StockLookupException e) {
        return org.springframework.http.ResponseEntity.badRequest().body(e.getMessage());
    }
}
