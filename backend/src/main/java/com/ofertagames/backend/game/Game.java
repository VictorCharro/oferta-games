package com.ofertagames.backend.game;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "games")
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "itad_id", unique = true)
    private UUID itadId;

    @Column(nullable = false)
    private String title;

    @Column(unique = true, nullable = false)
    private String slug;

    @Column(name = "cover_url")
    private String coverUrl;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public Game() {}

    public Game(UUID itadId, String title, String slug, String coverUrl) {
        this.itadId = itadId;
        this.title = title;
        this.slug = slug;
        this.coverUrl = coverUrl;
    }

    public Long getId() { return id; }
    public UUID getItadId() { return itadId; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getCoverUrl() { return coverUrl; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public void setItadId(UUID itadId) { this.itadId = itadId; }
    public void setTitle(String title) { this.title = title; }
    public void setSlug(String slug) { this.slug = slug; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
}
