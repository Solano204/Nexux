package com.nexus.identity.infrastructure.persistence;

import com.nexus.identity.domain.model.User;
import com.nexus.identity.domain.model.enums.UserStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    Optional<User> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);

    boolean existsByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    boolean existsByPhoneNumberAndDeletedAtIsNull(String phoneNumber);

    Optional<User> findByUserIdAndDeletedAtIsNull(UUID userId);

    @Query("""
        SELECT u FROM User u
        WHERE u.status = :status
        AND u.kycNextReviewAt <= :before
        AND u.deletedAt IS NULL
        ORDER BY u.kycNextReviewAt ASC
        """)
    List<User> findUsersForKycReview(@Param("status") UserStatus status,
                                     @Param("before") Instant before);

    @Modifying
    @Query("""
        UPDATE User u SET u.status = :status, u.updatedAt = :now
        WHERE u.userId = :userId
        """)
    int updateStatus(@Param("userId") UUID userId,
                     @Param("status") UserStatus status,
                     @Param("now") Instant now);
}