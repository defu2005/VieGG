package com.rentgg.catalog.infrastructure;

import com.rentgg.catalog.domain.Game;
import com.rentgg.catalog.domain.TosStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameRepository extends JpaRepository<Game, UUID> {

    Optional<Game> findByCode(String code);

    List<Game> findByTosStatusOrderByName(TosStatus status);

    List<Game> findAllByOrderByName();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Game g where g.id = :id")
    Optional<Game> findForUpdate(@Param("id") UUID id);

    /** True once any game account of this game holds an account key (the key rule can then no longer change). */
    @Query(value = "select exists(select 1 from game_accounts where game_id = :id and account_key is not null)",
            nativeQuery = true)
    boolean hasAccountKeys(@Param("id") UUID id);
}
