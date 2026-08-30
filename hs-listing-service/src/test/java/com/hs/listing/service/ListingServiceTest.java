package com.hs.listing.service;
import com.hs.common.advice.entity.AppException; import com.hs.listing.repository.*; import com.hs.storage.repository.StorageObjectRepository; import com.hs.user.repository.AddressRepository; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; import static org.mockito.Mockito.*;
class ListingServiceTest {
 @Test void rejectsUnauthenticatedCreate(){ListingService service=new ListingService(mock(ListingRepository.class),mock(AddressRepository.class),mock(StorageObjectRepository.class),mock(AmenityRepository.class),mock(FurnishingItemRepository.class));AppException ex=assertThrows(AppException.class,()->service.create(null,null));assertEquals(401,ex.getStatusCode().value());}
}
