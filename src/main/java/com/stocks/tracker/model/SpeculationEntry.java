package com.stocks.tracker.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A stock on the Speculation list: something being considered for purchase, tracked from the
 * moment it was added so its trend (and the quality of any AI recommendation) can be judged.
 * Nothing is owned, so there are no shares or per-account costs here.
 */
@Entity
@Table(name = "speculation_entry", uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "stock_id"}))
public class SpeculationEntry {

    public enum Source { USER, AI }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Nullable only so existing rows can be backfilled on upgrade; always set by application code. */
    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_id", nullable = false)
    private Stock stock;

    @Column(nullable = false)
    private LocalDateTime addedAt;

    /** Price in the stock's own currency when it was added. */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal addedPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Source addedBy;

    @Column(length = 1000)
    private String note;

    public SpeculationEntry() {
    }

    public SpeculationEntry(User owner, Stock stock, LocalDateTime addedAt, BigDecimal addedPrice, Source addedBy, String note) {
        this.owner = owner;
        this.stock = stock;
        this.addedAt = addedAt;
        this.addedPrice = addedPrice;
        this.addedBy = addedBy;
        this.note = note;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
    }

    public Stock getStock() {
        return stock;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }

    public BigDecimal getAddedPrice() {
        return addedPrice;
    }

    public Source getAddedBy() {
        return addedBy;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
