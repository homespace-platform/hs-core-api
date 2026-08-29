package com.hs.listing.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hs.listing.model.constant.ListingMediaType;
import com.hs.storage.config.StorageProperties;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@ExtendWith(MockitoExtension.class)
class ListingMediaServiceTest {

    @Mock
    S3Client s3Client;

    @Mock
    S3Presigner s3Presigner;

    private ListingMediaService listingMediaService;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties(
                "homespace-dev-files",
                "ap-southeast-1",
                Duration.ofMinutes(10),
                Duration.ofMinutes(5));
        listingMediaService = new ListingMediaService(properties, s3Client, s3Presigner);
    }

    @Test
    void buildsPublicUrlForListingImage() {
        String url = listingMediaService.buildPublicUrl("listing_image/user-1/file.png");
        assertEquals(
                "https://homespace-dev-files.s3.ap-southeast-1.amazonaws.com/listing_image/user-1/file.png",
                url);
    }

    @Test
    void acceptsOwnedListingMediaUrls() {
        String imageUrl = listingMediaService.buildPublicUrl("listing_image/user-1/file.png");
        String videoUrl = listingMediaService.buildPublicUrl("listing_video/user-1/file.mp4");

        assertTrue(listingMediaService.isAllowedMediaUrl("user-1", imageUrl, ListingMediaType.IMAGE));
        assertTrue(listingMediaService.isAllowedMediaUrl("user-1", videoUrl, ListingMediaType.VIDEO));
    }
}
