package com.hs.news.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.news.model.constant.NewsCategory;
import com.hs.news.model.constant.NewsStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "news", indexes = {
        @Index(name = "idx_news_status_published", columnList = "status, published_at"),
        @Index(name = "idx_news_category", columnList = "category")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class News extends BaseEntity {
    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, unique = true, length = 255)
    private String slug;

    @Column(nullable = false, length = 1000)
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NewsCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NewsStatus status;

    @Column(nullable = false)
    private boolean featured;

    @Column(name = "author_id", nullable = false, length = 36)
    private String authorId;

    @Column(name = "author_name", nullable = false, length = 150)
    private String authorName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_blocks", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<NewsContentBlock> contentBlocks = new ArrayList<>();

    @Column(name = "published_at")
    private Instant publishedAt;

    @OneToMany(mappedBy = "news", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    @Builder.Default
    private List<NewsMedia> media = new ArrayList<>();

    @Version
    private long version;
}
