package com.ofertagames.backend.offer;

import com.ofertagames.backend.game.Game;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "offers")
public class Offer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(nullable = false)
    private String source;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "regular_price", precision = 10, scale = 2)
    private BigDecimal regularPrice;

    @Column(nullable = false)
    private String currency = "BRL";

    @Column(nullable = false)
    private String url;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Offer() {}

    public Long getId() { return id; }
    public Game getGame() { return game; }
    public String getSource() { return source; }
    public String getStoreName() { return storeName; }
    public BigDecimal getPrice() { return price; }
    public BigDecimal getRegularPrice() { return regularPrice; }
    public String getCurrency() { return currency; }
    public String getUrl() { return url; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public void setGame(Game game) { this.game = game; }
    public void setSource(String source) { this.source = source; }
    public void setStoreName(String storeName) { this.storeName = storeName; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public void setRegularPrice(BigDecimal regularPrice) { this.regularPrice = regularPrice; }
    public void setCurrency(String currency) { this.currency = currency; }
    public void setUrl(String url) { this.url = url; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
