package dev.matthewsawyer.finance_dashboard.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "spending_plan_items")
public class SpendingPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "line_id", nullable = false)
    private SpendingPlanLine line;

    @Column(nullable = false)
    private String name;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private int position;

    /** Recurring stream the item was filled from; lets later stream changes become suggestions. */
    @Column(name = "stream_id")
    private String streamId;

    protected SpendingPlanItem() {
    }

    public SpendingPlanItem(String name, BigDecimal amount, String streamId) {
        this.name = name;
        this.amount = amount;
        this.streamId = streamId;
    }

    void attach(SpendingPlanLine line, int position) {
        this.line = line;
        this.position = position;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getPosition() {
        return position;
    }

    public String getStreamId() {
        return streamId;
    }
}
