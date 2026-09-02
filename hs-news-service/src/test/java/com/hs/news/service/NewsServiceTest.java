package com.hs.news.service;

import com.hs.news.dto.request.NewsContentBlockRequest;
import com.hs.news.dto.request.NewsUpsertRequest;
import com.hs.news.model.News;
import com.hs.news.model.NewsMedia;
import com.hs.news.model.constant.NewsBlockType;
import com.hs.news.model.constant.NewsCategory;
import com.hs.news.model.constant.NewsStatus;
import com.hs.news.model.constant.NewsMediaRole;
import com.hs.news.repository.NewsRepository;
import com.hs.common.advice.entity.AppException;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NewsServiceTest {
    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final StorageObjectRepository storageRepository = mock(StorageObjectRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final NewsService service = new NewsService(
            newsRepository,
            storageRepository,
            userRepository,
            new StorageProperties("homespace-dev-files", "ap-southeast-1", Duration.ofMinutes(10), Duration.ofMinutes(5)));

    @Test
    void createPublishedArticleLinksOwnedReadyPublicImages() {
        User author = new User();
        author.setId("admin-1");
        author.setFirstName("Home Space");
        author.setLastName("Admin");
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(author));
        when(newsRepository.existsBySlugIgnoreCaseAndActiveTrue("thi-truong-hom-nay")).thenReturn(false);
        when(storageRepository.findById("thumbnail-1")).thenReturn(Optional.of(image("thumbnail-1")));
        when(storageRepository.findById("content-1")).thenReturn(Optional.of(image("content-1")));
        when(newsRepository.save(any(News.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.create("admin-1", publishedRequest());

        assertEquals("thi-truong-hom-nay", response.slug());
        assertEquals(NewsStatus.PUBLISHED, response.status());
        assertEquals("Home Space Admin", response.authorName());
        assertNotNull(response.publishedAt());
        assertEquals(2, response.media().size());
        assertEquals("https://homespace-dev-files.s3.ap-southeast-1.amazonaws.com/news/thumbnail-1.webp",
                response.thumbnailUrl());

        ArgumentCaptor<News> saved = ArgumentCaptor.forClass(News.class);
        verify(newsRepository).save(saved.capture());
        assertEquals(2, saved.getValue().getMedia().size());
        assertEquals("NEWS", imageFromRepository("thumbnail-1").getReferenceType());
        assertEquals(saved.getValue().getId(), imageFromRepository("content-1").getReferenceId());
    }

    @Test
    void createRejectsImageOwnedByAnotherUser() {
        StorageObject thumbnail = image("thumbnail-1");
        thumbnail.setOwnerId("another-user");
        when(newsRepository.existsBySlugIgnoreCaseAndActiveTrue("thi-truong-hom-nay")).thenReturn(false);
        when(storageRepository.findById("thumbnail-1")).thenReturn(Optional.of(thumbnail));

        assertThrows(AppException.class, () -> service.create("admin-1", publishedRequest()));
        verify(newsRepository, never()).save(any());
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(newsRepository.existsBySlugIgnoreCaseAndActiveTrue("thi-truong-hom-nay")).thenReturn(true);

        assertThrows(AppException.class, () -> service.create("admin-1", publishedRequest()));
        verifyNoInteractions(storageRepository);
    }

    @Test
    void publishingRequiresThumbnail() {
        NewsUpsertRequest request = new NewsUpsertRequest(
                "Thị trường hôm nay", "thi-truong-hom-nay", "Tóm tắt", NewsCategory.MARKET,
                NewsStatus.PUBLISHED, false, List.of(), null,
                List.of(new NewsContentBlockRequest(
                        NewsBlockType.PARAGRAPH, "Nội dung", null, null, null)));
        when(newsRepository.existsBySlugIgnoreCaseAndActiveTrue("thi-truong-hom-nay")).thenReturn(false);

        assertThrows(AppException.class, () -> service.create("admin-1", request));
        verify(newsRepository, never()).save(any());
    }

    @Test
    void publicDetailDoesNotExposeDraftArticle() {
        when(newsRepository.findBySlugIgnoreCaseAndStatusAndActiveTrue("ban-nhap", NewsStatus.PUBLISHED))
                .thenReturn(Optional.empty());

        assertThrows(AppException.class, () -> service.getPublishedBySlug("ban-nhap"));
    }

    @Test
    void updateToDraftKeepsAuthorAndDetachesRemovedMedia() {
        StorageObject oldThumbnail = image("old-thumbnail");
        oldThumbnail.setReferenceType("NEWS");
        oldThumbnail.setReferenceId("news-1");
        News existing = News.builder()
                .id("news-1")
                .title("Tiêu đề cũ")
                .slug("tieu-de-cu")
                .summary("Tóm tắt cũ")
                .category(NewsCategory.MARKET)
                .status(NewsStatus.PUBLISHED)
                .featured(false)
                .authorId("original-admin")
                .authorName("Original Admin")
                .publishedAt(java.time.Instant.now())
                .build();
        existing.setActive(true);
        NewsMedia oldMedia = NewsMedia.builder()
                .id("media-1")
                .news(existing)
                .storageObject(oldThumbnail)
                .role(NewsMediaRole.THUMBNAIL)
                .sortOrder(0)
                .build();
        existing.getMedia().add(oldMedia);
        when(newsRepository.findByIdAndActiveTrue("news-1")).thenReturn(Optional.of(existing));
        when(newsRepository.existsBySlugIgnoreCaseAndIdNotAndActiveTrue("tieu-de-moi", "news-1"))
                .thenReturn(false);
        when(newsRepository.save(any(News.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NewsUpsertRequest request = new NewsUpsertRequest(
                "Tiêu đề mới", "tieu-de-moi", "Tóm tắt mới", NewsCategory.GUIDE,
                NewsStatus.DRAFT, false, List.of("hướng dẫn"), null,
                List.of(new NewsContentBlockRequest(
                        NewsBlockType.PARAGRAPH, "Nội dung đang soạn.", null, null, null)));

        var response = service.update("admin-1", "news-1", request);

        assertEquals("Original Admin", response.authorName());
        assertEquals(NewsStatus.DRAFT, response.status());
        assertNull(response.publishedAt());
        assertTrue(response.media().isEmpty());
        assertNull(oldThumbnail.getReferenceType());
        assertNull(oldThumbnail.getReferenceId());
    }

    @Test
    void updatePublishedArticleReusesUnchangedMedia() {
        StorageObject thumbnail = image("thumbnail-1");
        thumbnail.setReferenceType("NEWS");
        thumbnail.setReferenceId("news-1");
        News existing = News.builder()
                .id("news-1")
                .title("Tiêu đề cũ")
                .slug("tieu-de-cu")
                .summary("Tóm tắt cũ")
                .category(NewsCategory.MARKET)
                .status(NewsStatus.PUBLISHED)
                .authorId("admin-1")
                .authorName("Admin")
                .build();
        existing.setActive(true);
        NewsMedia unchangedMedia = NewsMedia.builder()
                .id("media-1")
                .news(existing)
                .storageObject(thumbnail)
                .role(NewsMediaRole.THUMBNAIL)
                .sortOrder(0)
                .build();
        existing.getMedia().add(unchangedMedia);
        when(newsRepository.findByIdAndActiveTrue("news-1")).thenReturn(Optional.of(existing));
        when(newsRepository.existsBySlugIgnoreCaseAndIdNotAndActiveTrue("tieu-de-moi", "news-1"))
                .thenReturn(false);
        when(storageRepository.findById("thumbnail-1")).thenReturn(Optional.of(thumbnail));
        when(newsRepository.save(any(News.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NewsUpsertRequest request = new NewsUpsertRequest(
                "Tiêu đề mới", "tieu-de-moi", "Tóm tắt mới", NewsCategory.MARKET,
                NewsStatus.PUBLISHED, false, List.of(), "thumbnail-1",
                List.of(new NewsContentBlockRequest(
                        NewsBlockType.PARAGRAPH, "Nội dung mới.", null, null, null)));

        service.update("admin-1", "news-1", request);

        assertSame(unchangedMedia, existing.getMedia().getFirst());
    }

    @Test
    void deleteSoftDeletesArticleAndDetachesMedia() {
        StorageObject thumbnail = image("thumbnail-delete");
        thumbnail.setReferenceType("NEWS");
        thumbnail.setReferenceId("news-delete");
        News existing = News.builder()
                .id("news-delete")
                .title("Bài sẽ xóa")
                .slug("bai-se-xoa")
                .summary("Tóm tắt")
                .category(NewsCategory.MARKET)
                .status(NewsStatus.DRAFT)
                .authorId("admin-1")
                .authorName("Admin")
                .build();
        existing.setActive(true);
        existing.getMedia().add(NewsMedia.builder()
                .id("media-delete")
                .news(existing)
                .storageObject(thumbnail)
                .role(NewsMediaRole.THUMBNAIL)
                .sortOrder(0)
                .build());
        when(newsRepository.findByIdAndActiveTrue("news-delete")).thenReturn(Optional.of(existing));

        service.delete("news-delete");

        assertFalse(existing.getActive());
        assertNull(thumbnail.getReferenceType());
        assertNull(thumbnail.getReferenceId());
    }

    private NewsUpsertRequest publishedRequest() {
        return new NewsUpsertRequest(
                "Thị trường hôm nay",
                "thi-truong-hom-nay",
                "Tóm tắt thị trường bất động sản.",
                NewsCategory.MARKET,
                NewsStatus.PUBLISHED,
                true,
                List.of("thị trường", "bất động sản"),
                "thumbnail-1",
                List.of(
                        new NewsContentBlockRequest(NewsBlockType.HEADING, "Tổng quan", null, null, null),
                        new NewsContentBlockRequest(NewsBlockType.IMAGE, null, "content-1", "Biểu đồ", "Số liệu tháng này"),
                        new NewsContentBlockRequest(NewsBlockType.PARAGRAPH, "Nội dung bài viết.", null, null, null)));
    }

    private StorageObject image(String id) {
        StorageObject object = StorageObject.builder()
                .id(id)
                .originalName(id + ".webp")
                .objectKey("news/" + id + ".webp")
                .bucketName("homespace-dev-files")
                .contentType("image/webp")
                .sizeBytes(1024L)
                .ownerId("admin-1")
                .purpose(StoragePurpose.NEWS_IMAGE)
                .visibility(StorageVisibility.PUBLIC)
                .status(StorageStatus.READY)
                .build();
        object.setActive(true);
        return object;
    }

    private StorageObject imageFromRepository(String id) {
        return storageRepository.findById(id).orElseThrow();
    }
}
