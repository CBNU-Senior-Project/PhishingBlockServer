package com.phishing.authservice.service;

import com.phishing.authservice.component.token.ReturnToken;
import com.phishing.authservice.component.token.TokenProvider;
import com.phishing.authservice.component.token.TokenResolver;
import com.phishing.authservice.domain.User;
import com.phishing.authservice.payload.request.SignInRequest;
import com.phishing.authservice.exception.exceptions.InvalidPasswordException;
import com.phishing.authservice.redis.RedisDao;
import com.phishing.authservice.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RedisDao redisDao;
    private final PasswordEncoder passwordEncoder;
    private final TokenProvider tokenProvider;
    private final TokenResolver tokenResolver;

    @Value("${jwt.secret.refresh}")
    private Long refreshTime;

    public ReturnToken signIn(SignInRequest request) {
        // Check if email isn't exists
        userRepository.existsByEmail(request.email());
        // Check if password is correct
        User loginUser = userRepository.findByEmailAndIsDeletedIsFalse(request.email())
                .filter(user -> passwordEncoder.matches(request.password(), user.getPassword()))
                .orElseThrow(() -> new InvalidPasswordException("Invalid password"));
        // return jwt token
        ReturnToken returnToken = tokenProvider.provideTokens(loginUser);
        // save refresh token in redis
        redisDao.setRedisValues(loginUser.getEmail(),
                returnToken.refreshToken(), Duration.ofMillis(refreshTime));

        return returnToken;
    }

    public void signOut(HttpServletRequest request) {
        // Get user id from jwt token
        String accessToken = request.getHeader("Authorization");
        String refreshToken = request.getHeader("RefreshToken");
        // set refresh token's blacklist ttl
        long remainTime = tokenResolver.getExpiration(refreshToken);
        long ttl = remainTime - System.currentTimeMillis();
        // Get user email from jwt token
        String email = tokenResolver.getAccessClaims(accessToken).email();
        // Delete refresh token in redis
        if (redisDao.isExistKey(email)) {
            redisDao.deleteRedisValues(email);
        }
        // save refresh token to blacklist
        redisDao.setRedisValues("Blacklist_" + email, refreshToken, Duration.ofMillis(ttl));
    }

    public ReturnToken refresh(HttpServletRequest request) {
        // Get user id from jwt token
        String refreshToken = request.getHeader("RefreshToken");
        // Get user email from jwt token
        String email = tokenResolver.getRefreshClaims(refreshToken).email();
        // Check if refresh token is valid
        if (!redisDao.isExistKey(email) || !redisDao.getRedisValues(email).equals(refreshToken)) {
            throw new InvalidPasswordException("Invalid refresh token");
        }
        // return jwt token
        User loginUser = userRepository.findByEmailAndIsDeletedIsFalse(email)
                .orElseThrow(() -> new InvalidPasswordException("Invalid refresh token"));
        ReturnToken returnToken = tokenProvider.provideTokens(loginUser);
        // save refresh token in redis
        redisDao.setRedisValues(loginUser.getEmail(),
                returnToken.refreshToken(), Duration.ofMillis(refreshTime));

        return returnToken;
    }
}
