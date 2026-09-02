package com.hs.news.model;

import com.hs.common.persistence.BaseEntity;
import com.hs.news.model.constant.NewsMediaRole;
import com.hs.storage.model.StorageObject;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "news_media", uniqueConstraints = {
        @UniqueConstraint(name = "uk_news_media_storage", columnNames = {"news_id", "storage_object_id"})
}, indexes = {
        @Index(name = "idx_news_media_news", columnList = "news_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsMedia extends BaseEntity {
    @Id
    @Column(length = 36, updatable = false)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "news_id", nullable = false)
    private News news;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "storage_object_id", nullable = false)
    private StorageObject storageObject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NewsMediaRole role;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "alt_text", length = 500)
    private String altText;

    @Column(length = 1000)
    private String caption;
}
