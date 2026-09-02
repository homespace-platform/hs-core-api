package com.hs.news.repository;

import com.hs.news.model.News;
import com.hs.news.model.constant.NewsStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface NewsRepository extends JpaRepository<News, String>, JpaSpecificationExecutor<News> {
    boolean existsBySlugIgnoreCaseAndActiveTrue(String slug);

    boolean existsBySlugIgnoreCaseAndIdNotAndActiveTrue(String slug, String id);

    Optional<News> findByIdAndActiveTrue(String id);

    Optional<News> findBySlugIgnoreCaseAndStatusAndActiveTrue(String slug, NewsStatus status);
}
