package com.hs.news.service;

import com.hs.common.advice.entity.AppException;
import com.hs.common.dto.PageResponse;
import com.hs.news.advice.NewsErrorCode;
import com.hs.news.dto.request.NewsContentBlockRequest;
import com.hs.news.dto.request.NewsUpsertRequest;
import com.hs.news.dto.response.NewsMediaResponse;
import com.hs.news.dto.response.NewsResponse;
import com.hs.news.dto.response.NewsSummaryResponse;
import com.hs.news.model.News;
import com.hs.news.model.NewsContentBlock;
import com.hs.news.model.NewsMedia;
import com.hs.news.model.constant.NewsBlockType;
import com.hs.news.model.constant.NewsCategory;
import com.hs.news.model.constant.NewsMediaRole;
import com.hs.news.model.constant.NewsStatus;
import com.hs.news.repository.NewsRepository;
import com.hs.storage.config.StorageProperties;
import com.hs.storage.model.StorageObject;
import com.hs.storage.model.constant.StoragePurpose;
import com.hs.storage.model.constant.StorageStatus;
import com.hs.storage.model.constant.StorageVisibility;
import com.hs.storage.repository.StorageObjectRepository;
import com.hs.user.model.User;
import com.hs.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class NewsService {
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "publishedAt", "title");
    private final NewsRepository newsRepository;
    private final StorageObjectRepository storageRepository;
    private final UserRepository userRepository;
    private final StorageProperties storageProperties;

    @Transactional
    public NewsResponse create(String authorId, NewsUpsertRequest request) {
        requireAuthor(authorId);
        String slug = normalizeSlug(request.slug());
        if (newsRepository.existsBySlugIgnoreCaseAndActiveTrue(slug)) {
            throw new AppException(NewsErrorCode.NEWS_SLUG_EXISTS);
        }
        validateContent(request);

        News news = News.builder()
                .id(UUID.randomUUID().toString())
                .title(request.title().trim())
                .slug(slug)
                .summary(request.summary().trim())
                .category(request.category())
                .status(request.status())
                .featured(Boolean.TRUE.equals(request.featured()))
                .tags(normalizeTags(request.tags()))
                .authorId(authorId)
                .authorName(resolveAuthorName(authorId))
                .publishedAt(request.status() == NewsStatus.PUBLISHED ? Instant.now() : null)
                .build();
        news.setActive(true);
        attachContentAndMedia(news, authorId, request);
        return toResponse(newsRepository.save(news));
    }

    @Transactional(readOnly = true)
    public NewsResponse getPublishedBySlug(String slug) {
        return newsRepository.findBySlugIgnoreCaseAndStatusAndActiveTrue(
                        normalizeSlug(slug), NewsStatus.PUBLISHED)
                .map(this::toResponse)
                .orElseThrow(() -> new AppException(NewsErrorCode.NEWS_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public NewsResponse getAdmin(String newsId) {
        return newsRepository.findByIdAndActiveTrue(newsId)
                .map(this::toResponse)
                .orElseThrow(() -> new AppException(NewsErrorCode.NEWS_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public PageResponse<NewsSummaryResponse> findAdmin(
            int page,
            int size,
            NewsStatus status,
            NewsCategory category,
            String keyword,
            String sort) {
        Specification<News> specification = activeSpecification();
        if (status != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (category != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        specification = withKeyword(specification, keyword);
        return new PageResponse<>(newsRepository.findAll(
                specification,
                PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100), resolveSort(sort)))
                .map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public PageResponse<NewsSummaryResponse> findPublished(
            int page,
            int size,
            NewsCategory category,
            String keyword) {
        Specification<News> specification = activeSpecification()
                .and((root, query, cb) -> cb.equal(root.get("status"), NewsStatus.PUBLISHED));
        if (category != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("category"), category));
        }
        specification = withKeyword(specification, keyword);
        Sort sort = Sort.by(Sort.Order.desc("featured"), Sort.Order.desc("publishedAt"));
        return new PageResponse<>(newsRepository.findAll(
                specification,
                PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 50), sort))
                .map(this::toSummary));
    }

    @Transactional
    public NewsResponse update(String actorId, String newsId, NewsUpsertRequest request) {
        requireAuthor(actorId);
        News news = newsRepository.findByIdAndActiveTrue(newsId)
                .orElseThrow(() -> new AppException(NewsErrorCode.NEWS_NOT_FOUND));
        String slug = normalizeSlug(request.slug());
        if (newsRepository.existsBySlugIgnoreCaseAndIdNotAndActiveTrue(slug, newsId)) {
            throw new AppException(NewsErrorCode.NEWS_SLUG_EXISTS);
        }
        validateContent(request);

        news.setTitle(request.title().trim());
        news.setSlug(slug);
        news.setSummary(request.summary().trim());
        news.setCategory(request.category());
        news.setStatus(request.status());
        news.setFeatured(Boolean.TRUE.equals(request.featured()));
        news.setTags(normalizeTags(request.tags()));
        news.setPublishedAt(request.status() == NewsStatus.PUBLISHED
                ? Optional.ofNullable(news.getPublishedAt()).orElseGet(Instant::now)
                : null);
        attachContentAndMedia(news, actorId, request);
        return toResponse(newsRepository.save(news));
    }

    @Transactional
    public void delete(String newsId) {
        News news = newsRepository.findByIdAndActiveTrue(newsId)
                .orElseThrow(() -> new AppException(NewsErrorCode.NEWS_NOT_FOUND));
        for (NewsMedia media : news.getMedia()) {
            StorageObject object = media.getStorageObject();
            if ("NEWS".equals(object.getReferenceType()) && news.getId().equals(object.getReferenceId())) {
                object.setReferenceType(null);
                object.setReferenceId(null);
            }
            media.setActive(false);
        }
        news.setActive(false);
        newsRepository.save(news);
    }

    private Specification<News> activeSpecification() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    private Specification<News> withKeyword(Specification<News> specification, String keyword) {
        if (keyword == null || keyword.isBlank()) return specification;
        String pattern = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
        return specification.and((root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern),
                cb.like(cb.lower(root.get("summary")), pattern)));
    }

    private Sort resolveSort(String value) {
        if (value == null || value.isBlank()) return Sort.by(Sort.Direction.DESC, "createdAt");
        String[] parts = value.split(",", 2);
        String field = ALLOWED_SORT_FIELDS.contains(parts[0]) ? parts[0] : "createdAt";
        Sort.Direction direction = parts.length == 2 && "asc".equalsIgnoreCase(parts[1])
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }

    private void attachContentAndMedia(News news, String authorId, NewsUpsertRequest request) {
        List<NewsMedia> currentMedia = new ArrayList<>(news.getMedia());
        Map<String, NewsMedia> currentMediaByStorageId = new HashMap<>();
        for (NewsMedia media : currentMedia) {
            currentMediaByStorageId.put(media.getStorageObject().getId(), media);
        }
        List<NewsMedia> requestedMedia = new ArrayList<>();
        Set<String> usedStorageIds = new HashSet<>();
        if (request.thumbnailStorageObjectId() != null && !request.thumbnailStorageObjectId().isBlank()) {
            requestedMedia.add(attachMedia(news, authorId, request.thumbnailStorageObjectId(),
                    NewsMediaRole.THUMBNAIL, 0, null, null, usedStorageIds, currentMediaByStorageId));
        }

        List<NewsContentBlock> blocks = new ArrayList<>();
        for (int index = 0; index < request.contentBlocks().size(); index++) {
            NewsContentBlockRequest block = request.contentBlocks().get(index);
            if (block.type() == NewsBlockType.IMAGE) {
                requestedMedia.add(attachMedia(news, authorId, block.storageObjectId(), NewsMediaRole.CONTENT,
                        index, block.altText(), block.caption(), usedStorageIds, currentMediaByStorageId));
            }
            blocks.add(new NewsContentBlock(
                    block.type(), normalize(block.text()), normalize(block.storageObjectId()),
                    normalize(block.altText()), normalize(block.caption())));
        }
        for (NewsMedia media : currentMedia) {
            if (!usedStorageIds.contains(media.getStorageObject().getId())) {
                StorageObject object = media.getStorageObject();
                if ("NEWS".equals(object.getReferenceType()) && news.getId().equals(object.getReferenceId())) {
                    object.setReferenceType(null);
                    object.setReferenceId(null);
                }
            }
        }
        news.getMedia().clear();
        news.getMedia().addAll(requestedMedia);
        news.setContentBlocks(blocks);
    }

    private NewsMedia attachMedia(
            News news,
            String authorId,
            String storageId,
            NewsMediaRole role,
            int sortOrder,
            String altText,
            String caption,
            Set<String> usedStorageIds,
            Map<String, NewsMedia> currentMediaByStorageId) {
        if (storageId == null || storageId.isBlank() || !usedStorageIds.add(storageId)) {
            throw new AppException(NewsErrorCode.NEWS_INVALID_MEDIA);
        }
        StorageObject object = storageRepository.findById(storageId)
                .orElseThrow(() -> new AppException(NewsErrorCode.NEWS_INVALID_MEDIA));
        if (!authorId.equals(object.getOwnerId())) {
            throw new AppException(NewsErrorCode.NEWS_MEDIA_FORBIDDEN);
        }
        if (!Boolean.TRUE.equals(object.getActive())
                || object.getStatus() != StorageStatus.READY
                || object.getVisibility() != StorageVisibility.PUBLIC
                || object.getPurpose() != StoragePurpose.NEWS_IMAGE
                || object.getContentType() == null
                || !object.getContentType().startsWith("image/")) {
            throw new AppException(NewsErrorCode.NEWS_INVALID_MEDIA);
        }

        object.setReferenceType("NEWS");
        object.setReferenceId(news.getId());
        NewsMedia media = currentMediaByStorageId.get(storageId);
        if (media == null) {
            media = NewsMedia.builder()
                    .id(UUID.randomUUID().toString())
                    .news(news)
                    .storageObject(object)
                    .build();
        }
        media.setRole(role);
        media.setSortOrder(sortOrder);
        media.setAltText(normalize(altText));
        media.setCaption(normalize(caption));
        media.setActive(true);
        return media;
    }

    private void validateContent(NewsUpsertRequest request) {
        if (request.contentBlocks() == null) {
            throw new AppException(NewsErrorCode.NEWS_INVALID_CONTENT);
        }
        if (request.status() == NewsStatus.PUBLISHED) {
            if (request.thumbnailStorageObjectId() == null || request.thumbnailStorageObjectId().isBlank()) {
                throw new AppException(NewsErrorCode.NEWS_INVALID_CONTENT);
            }
            boolean hasContent = request.contentBlocks().stream().anyMatch(block ->
                    block.type() == NewsBlockType.IMAGE
                            ? block.storageObjectId() != null && !block.storageObjectId().isBlank()
                            : block.text() != null && !block.text().isBlank());
            if (!hasContent) {
                throw new AppException(NewsErrorCode.NEWS_INVALID_CONTENT);
            }
        }
    }

    private void requireAuthor(String authorId) {
        if (authorId == null || authorId.isBlank()) {
            throw new AppException(NewsErrorCode.NEWS_AUTHENTICATION_REQUIRED);
        }
    }

    private String resolveAuthorName(String authorId) {
        return userRepository.findById(authorId)
                .map(this::displayName)
                .orElse("Admin");
    }

    private String displayName(User user) {
        String fullName = String.join(" ",
                user.getFirstName() == null ? "" : user.getFirstName().trim(),
                user.getLastName() == null ? "" : user.getLastName().trim()).trim();
        if (!fullName.isBlank()) return fullName;
        return user.getUsername() == null || user.getUsername().isBlank() ? "Admin" : user.getUsername();
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) return new ArrayList<>();
        return tags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(tag -> !tag.isBlank())
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private String normalizeSlug(String slug) {
        return slug.trim().toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private NewsResponse toResponse(News news) {
        List<NewsMediaResponse> media = news.getMedia().stream()
                .sorted(Comparator.comparingInt(NewsMedia::getSortOrder))
                .map(item -> new NewsMediaResponse(
                        item.getId(), item.getStorageObject().getId(), item.getRole(), item.getSortOrder(),
                        item.getAltText(), item.getCaption(), publicUrl(item.getStorageObject())))
                .toList();
        String thumbnailUrl = news.getMedia().stream()
                .filter(item -> item.getRole() == NewsMediaRole.THUMBNAIL)
                .findFirst()
                .map(item -> publicUrl(item.getStorageObject()))
                .orElse(null);
        return new NewsResponse(
                news.getId(), news.getTitle(), news.getSlug(), news.getSummary(), news.getCategory(),
                news.getStatus(), news.isFeatured(), news.getTags(), news.getContentBlocks(), thumbnailUrl,
                media, news.getAuthorId(), news.getAuthorName(), news.getPublishedAt(),
                news.getCreatedAt(), news.getUpdatedAt());
    }

    private NewsSummaryResponse toSummary(News news) {
        String thumbnailUrl = news.getMedia().stream()
                .filter(item -> item.getRole() == NewsMediaRole.THUMBNAIL)
                .findFirst()
                .map(item -> publicUrl(item.getStorageObject()))
                .orElse(null);
        return new NewsSummaryResponse(
                news.getId(), news.getTitle(), news.getSlug(), news.getSummary(), news.getCategory(),
                news.getStatus(), news.isFeatured(), news.getTags(), thumbnailUrl,
                news.getAuthorName(), news.getPublishedAt(), news.getCreatedAt());
    }

    private String publicUrl(StorageObject object) {
        if (!Boolean.TRUE.equals(object.getActive()) || object.getVisibility() != StorageVisibility.PUBLIC) return null;
        return "https://%s.s3.%s.amazonaws.com/%s"
                .formatted(object.getBucketName(), storageProperties.region(), object.getObjectKey());
    }
}
