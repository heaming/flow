package com.signup.flow.service;

import com.signup.flow.FlowApplication;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static com.signup.flow.exception.ErrorCode.QUEUE_ALREADY_REGISTERED_USER;

@Service
@RequiredArgsConstructor
public class UserQueueService {
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private final String USER_QUEUE_WAIT_KEY = "users:queue:%s:wait";
    private final String USER_QUEUE_PROCEED_KEY = "users:queue:%s:proceed";

    // 대기열 등록 API
    public Mono<Long> registerWaitQueue(final String queue, final Long userId) {
        // redis sortedset
        // - key: userId
        // - value: unix timestamp
        // rank
        var unixTimestamp = Instant.now().getEpochSecond();
        return reactiveRedisTemplate.opsForZSet()
                .add(USER_QUEUE_WAIT_KEY.formatted(queue), userId.toString(), unixTimestamp)
                .filter(i -> i) // 동일 값이 있는 경우 false 반환 -> i == true인 경우만 진행
                .switchIfEmpty(Mono.error(QUEUE_ALREADY_REGISTERED_USER.build()))
                .flatMap(i -> reactiveRedisTemplate.opsForZSet().rank(USER_QUEUE_WAIT_KEY.formatted(queue), userId.toString()))
                .map(i -> i >= 0 ? i+1 : i);
    }

    // 진입 요청 API
    // - 진입이 가능한 상태인지 조회
    // - 진입 허용
    /**
     * @Title 진입 요청
     * @param queue
     * @param count 몇 개의 유저를 허용할 것인지 정의
     * @return Long 몇 명이 허용되었는지
     */
    public Mono<Long> allowUser(final String queue, final Long count) {
        // wait queue 사용자 제거
        // proceed queue 사용자 추가
        return reactiveRedisTemplate.opsForZSet()
                .popMin(USER_QUEUE_WAIT_KEY.formatted(queue), count)
                .flatMap(member -> reactiveRedisTemplate.opsForZSet()
                        .add(USER_QUEUE_PROCEED_KEY.formatted(queue), member.getValue(), Instant.now().getEpochSecond()))
                .count();
    }

    /**
     * @Title 진입 가능 여부 확인
     * @param queue
     * @param userId
     * @return Boolean
     */
    public Mono<Boolean> isAllowed(final String queue, final Long userId) {
        return reactiveRedisTemplate.opsForZSet()
                .rank(USER_QUEUE_PROCEED_KEY.formatted(queue), userId.toString())
                .defaultIfEmpty(-1L)
                .map(rank -> rank >= 0);
    }

    /**
     * @Title 대기열 순번 확인
     * @param queue
     * @param userId
     * @return Long
     */
    public Mono<Long> getRank(final String queue, final Long userId) {
        return reactiveRedisTemplate.opsForZSet()
                .rank(USER_QUEUE_WAIT_KEY.formatted(queue), userId.toString())
                .defaultIfEmpty(-1L)
                .map(rank -> rank >= 0 ? rank+1: rank);
    }




}
