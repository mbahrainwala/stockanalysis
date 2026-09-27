package com.stocks.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocks.tracker.model.Market;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pulls Wall Street analyst consensus, recent analyst actions and news headlines for a stock from
 * Yahoo Finance. The analyst endpoints need a session cookie and "crumb" token, which are fetched
 * once and reused. Everything is best-effort: missing data yields a partial result, not an error.
 */
@Service
public class ExternalRatingService {

    private static final Logger log = LoggerFactory.getLogger(ExternalRatingService.class);
    private static final long CACHE_MS = Duration.ofMinutes(15).toMillis();

    public record Counts(int strongBuy, int buy, int hold, int sell, int strongSell) {
    }

    public record Targets(Double mean, Double high, Double low) {
    }

    public record Action(String date, String firm, String action, String fromGrade, String toGrade, Double priceTarget) {
    }

    public record NewsItem(String title, String publisher, String link, String date) {
    }

    /** consensusKey is one of strong_buy, buy, hold, sell, strong_sell, or null when there is no coverage. */
    public record Rating(String consensus, String consensusKey, Double mean, Integer analystCount, Counts counts,
                         Targets targets, List<Action> actions, List<NewsItem> news) {
    }

    private record Cached(Rating rating, long at) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final YahooSession yahoo;

    public ExternalRatingService(YahooSession yahoo) {
        this.yahoo = yahoo;
    }

    public Rating lookup(String symbol, Market market) {
        String yahooSymbol = market.toYahooSymbol(symbol);
        Cached cached = cache.get(yahooSymbol);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MS) {
            return cached.rating();
        }
        Rating rating = fetch(yahooSymbol, symbol);
        cache.put(yahooSymbol, new Cached(rating, System.currentTimeMillis()));
        return rating;
    }

    /** Drops cached analyst data so the next lookup fetches fresh information. */
    public void invalidate() {
        cache.clear();
    }

    private Rating fetch(String yahooSymbol, String bareSymbol) {
        String consensusKey = null;
        Double mean = null;
        Integer count = null;
        Counts counts = null;
        Targets targets = null;
        List<Action> actions = new ArrayList<>();
        try {
            JsonNode result = quoteSummary(yahooSymbol);
            if (result != null) {
                JsonNode fin = result.path("financialData");
                mean = num(fin.path("recommendationMean"));
                Double n = num(fin.path("numberOfAnalystOpinions"));
                count = n == null ? null : n.intValue();
                targets = new Targets(num(fin.path("targetMeanPrice")), num(fin.path("targetHighPrice")), num(fin.path("targetLowPrice")));
                JsonNode now = result.path("recommendationTrend").path("trend").path(0);
                if (!now.isMissingNode()) {
                    counts = new Counts(now.path("strongBuy").asInt(), now.path("buy").asInt(), now.path("hold").asInt(),
                            now.path("sell").asInt(), now.path("strongSell").asInt());
                }
                if (counts != null && total(counts) > 0) {
                    count = total(counts); // the breakdown is more reliable than the separate opinion count
                }
                if (mean != null) {
                    consensusKey = keyFor(mean);
                } else if (counts != null && total(counts) > 0) {
                    consensusKey = keyFor(meanOf(counts));
                    mean = meanOf(counts);
                }
                JsonNode hist = result.path("upgradeDowngradeHistory").path("history");
                for (int i = 0; i < hist.size() && actions.size() < 8; i++) {
                    JsonNode h = hist.get(i);
                    actions.add(new Action(
                            date(h.path("epochGradeDate").asLong(0)),
                            h.path("firm").asText(""),
                            actionLabel(h.path("action").asText("")),
                            h.path("fromGrade").asText(""),
                            h.path("toGrade").asText(""),
                            h.path("currentPriceTarget").asDouble(0) > 0 ? h.path("currentPriceTarget").asDouble() : null));
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch analyst data for {}: {}", yahooSymbol, e.getMessage());
        }
        return new Rating(label(consensusKey), consensusKey, mean, count, counts, targets, actions, news(yahooSymbol, bareSymbol));
    }

    // ---- Yahoo access ----

    private JsonNode quoteSummary(String yahooSymbol) throws java.io.IOException, InterruptedException {
        JsonNode root = yahoo.getJson("https://query2.finance.yahoo.com/v10/finance/quoteSummary/" + enc(yahooSymbol)
                + "?modules=financialData,recommendationTrend,upgradeDowngradeHistory", true);
        JsonNode res = root == null ? null : root.path("quoteSummary").path("result").path(0);
        return res == null || res.isMissingNode() ? null : res;
    }

    private List<NewsItem> news(String yahooSymbol, String bareSymbol) {
        List<NewsItem> items = new ArrayList<>();
        try {
            JsonNode root = yahoo.getJson("https://query1.finance.yahoo.com/v1/finance/search?q=" + enc(yahooSymbol)
                    + "&quotesCount=0&newsCount=15", false);
            if (root == null) {
                return items;
            }
            for (JsonNode n : root.path("news")) {
                // The search is loose, so keep only stories actually tagged with this ticker.
                boolean related = false;
                for (JsonNode t : n.path("relatedTickers")) {
                    if (t.asText("").equalsIgnoreCase(yahooSymbol) || t.asText("").equalsIgnoreCase(bareSymbol)) {
                        related = true;
                    }
                }
                String link = n.path("link").asText("");
                if (related && link.startsWith("http") && items.size() < 6) {
                    items.add(new NewsItem(n.path("title").asText(""), n.path("publisher").asText(""), link,
                            date(n.path("providerPublishTime").asLong(0))));
                }
            }
        } catch (Exception e) {
            log.warn("Could not fetch news for {}: {}", yahooSymbol, e.getMessage());
        }
        return items;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- Helpers ----

    /** Yahoo wraps numbers as {"raw": 1.0, "fmt": "1.00"}; missing/empty values are null. */
    private static Double num(JsonNode node) {
        JsonNode raw = node.isObject() ? node.path("raw") : node;
        return raw.isNumber() ? raw.asDouble() : null;
    }

    private static int total(Counts c) {
        return c.strongBuy() + c.buy() + c.hold() + c.sell() + c.strongSell();
    }

    private static double meanOf(Counts c) {
        return (c.strongBuy() + 2.0 * c.buy() + 3.0 * c.hold() + 4.0 * c.sell() + 5.0 * c.strongSell()) / total(c);
    }

    /** Maps the 1 (strong buy) to 5 (strong sell) consensus score to a rating. */
    static String keyFor(double mean) {
        if (mean <= 1.5) {
            return "strong_buy";
        }
        if (mean <= 2.5) {
            return "buy";
        }
        if (mean <= 3.5) {
            return "hold";
        }
        return mean <= 4.5 ? "sell" : "strong_sell";
    }

    private static String label(String key) {
        if (key == null) {
            return null;
        }
        return switch (key) {
            case "strong_buy" -> "Strong Buy";
            case "buy" -> "Buy";
            case "hold" -> "Hold";
            case "sell" -> "Sell";
            default -> "Strong Sell";
        };
    }

    private static String actionLabel(String code) {
        return switch (code) {
            case "up" -> "Upgrade";
            case "down" -> "Downgrade";
            case "init" -> "Initiated";
            case "main" -> "Maintained";
            case "reit" -> "Reiterated";
            default -> code;
        };
    }

    private static String date(long epochSeconds) {
        return epochSeconds <= 0 ? "" : LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC).toString();
    }
}
