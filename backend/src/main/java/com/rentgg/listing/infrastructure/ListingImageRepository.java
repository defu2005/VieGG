package com.rentgg.listing.infrastructure;

import com.rentgg.listing.domain.ListingImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ListingImageRepository extends JpaRepository<ListingImage, UUID> {

    /** Ids only, so listing pages never load the image bytes. */
    interface ImageRef {
        UUID getId();
        UUID getListingId();
    }

    @Query("select i.id as id, i.listingId as listingId from ListingImage i where i.listingId in :listingIds "
            + "order by i.listingId, i.position, i.createdAt")
    List<ImageRef> findRefs(@Param("listingIds") Collection<UUID> listingIds);

    @Query("select count(i) from ListingImage i where i.listingId = :listingId")
    int countByListing(@Param("listingId") UUID listingId);

    @Query("select coalesce(max(i.position), 0) from ListingImage i where i.listingId = :listingId")
    int maxPosition(@Param("listingId") UUID listingId);
}
