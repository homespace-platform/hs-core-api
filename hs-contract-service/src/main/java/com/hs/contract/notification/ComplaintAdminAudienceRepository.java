package com.hs.contract.notification;

import com.hs.user.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** ComplaintAdminController authorizes ADMIN; do not load profiles or private KYC data for its audience. */
public interface ComplaintAdminAudienceRepository extends Repository<User,String> {
    @Query("select u.id from User u join u.role r where u.active = true and r.active = true and r.name = 'ADMIN' "
        + "and (:tenant is null or u.id <> :tenant) and (:owner is null or u.id <> :owner) "
        + "and (:afterId is null or u.id > :afterId) order by u.id")
    List<String> findIdsAfter(@Param("tenant") String tenant,@Param("owner") String owner,
                              @Param("afterId") String afterId,Pageable page);

    @Query("select (count(u)>0) from User u join u.role r where u.id = :id and u.active = true and r.active = true and r.name = 'ADMIN'")
    boolean isActiveAdmin(@Param("id") String id);
}
