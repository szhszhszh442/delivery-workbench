package io.projectforjob.businessapi.api;

import java.util.List;

public record Paging(int page, int pageSize) {

    public Paging {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw ApiFailure.invalid();
        }
    }

    public long offset() {
        return ((long) page - 1) * pageSize;
    }

    public <T> Page<T> result(List<T> items, long total) {
        return new Page<>(items, page, pageSize, total);
    }

    public record Page<T>(List<T> items, int page, int pageSize, long total) {
    }
}
