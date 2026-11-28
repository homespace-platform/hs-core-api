package com.hs.listing.repository;

import com.hs.listing.model.ListingViewDaily;
import java.time.LocalDate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ListingViewDailyRepository extends JpaRepository<ListingViewDaily, ListingViewDaily.Key> {
    /** The unique viewer insert and both daily counters commit with the lifetime counter. */
    @Modifying
    @Query(value = "with new_viewer as (insert into listing_view_daily_viewers (listing_id, view_date, viewer_id) "
            + "select :listingId, cast(:day as date), cast(:viewerId as varchar) where cast(:viewerId as varchar) is not null "
            + "on conflict do nothing returning 1) "
            + "insert into listing_view_daily (listing_id, view_date, views, unique_logged_in_viewers) "
            + "values (:listingId, :day, 1, (select count(*) from new_viewer)) "
            + "on conflict (listing_id, view_date) do update set views = listing_view_daily.views + 1, "
            + "unique_logged_in_viewers = listing_view_daily.unique_logged_in_viewers + excluded.unique_logged_in_viewers",
            nativeQuery = true)
    void record(@Param("listingId") String listingId, @Param("day") LocalDate day, @Param("viewerId") String viewerId);
}
