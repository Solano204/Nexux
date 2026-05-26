package com.nexus.ledger.infrastructure.persistence;

import com.nexus.ledger.domain.model.ChartOfAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChartOfAccountRepository
        extends JpaRepository<ChartOfAccount, UUID> {

    Optional<ChartOfAccount> findByAccountIdAndIsActiveTrue(UUID accountId);

    Optional<ChartOfAccount> findByAccountNumber(String accountNumber);

    Optional<ChartOfAccount> findByUserIdAndAccountSubtype(
            UUID userId, String accountSubtype);

    List<ChartOfAccount> findByIsUserAccountTrueAndIsActiveTrue();

    List<ChartOfAccount> findByIsUserAccountFalse();
}