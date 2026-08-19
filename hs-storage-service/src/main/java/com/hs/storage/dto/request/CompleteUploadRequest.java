package com.hs.storage.dto.request;

import jakarta.validation.constraints.Size;

public record CompleteUploadRequest(@Size(max = 128) String checksum) {
}
