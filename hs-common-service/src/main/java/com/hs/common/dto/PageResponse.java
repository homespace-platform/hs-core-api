package com.hs.common.dto;

import java.util.List;
import org.springframework.data.domain.Page;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor
public class PageResponse<T> extends ApiResponse<List<T>> {
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasMore;

    public PageResponse(Page<T> source) {
        setResult(source.getContent());
        page = source.getNumber() + 1;
        size = source.getSize();
        totalElements = source.getTotalElements();
        totalPages = source.getTotalPages();
        hasMore = page < totalPages;
    }
}
