package com.stocks.tracker.dto;

import com.stocks.tracker.model.Market;
import com.stocks.tracker.model.SpeculationEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One line of the Speculation list; prices are in the stock's own currency. */
public record SpeculationRow(Long id, String symbol, Market market, String companyName, String currencySymbol,
                             LocalDateTime addedAt, long daysHeld, BigDecimal addedPrice, BigDecimal currentPrice,
                             BigDecimal changePercent, SpeculationEntry.Source addedBy, String note) {

    public boolean isAi() {
        return addedBy == SpeculationEntry.Source.AI;
    }
}
