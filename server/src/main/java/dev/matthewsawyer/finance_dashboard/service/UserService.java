package dev.matthewsawyer.finance_dashboard.service;

import dev.matthewsawyer.finance_dashboard.model.User;
import dev.matthewsawyer.finance_dashboard.repository.UserRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User getOrCreateUser(Jwt jwt) {
        return userRepository.findByClerkUserId(jwt.getSubject())
                .orElseGet(() -> userRepository.save(new User(jwt.getSubject())));
    }
}
