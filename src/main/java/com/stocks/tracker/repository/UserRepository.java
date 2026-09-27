package com.stocks.tracker.repository;

import com.stocks.tracker.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsernameIgnoreCase(String username);

    List<User> findAllByOrderByUsernameAsc();
}
