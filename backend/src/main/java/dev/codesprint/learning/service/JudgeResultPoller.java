package dev.codesprint.learning.service;

import dev.codesprint.judge.JudgeJobRepository;
import dev.codesprint.judge.JudgeJobRow;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 채점이 끝난 job 을 찾아 학습 상태에 반영한다.
 *
 * <p>Worker(Python)는 큐에 결과만 쓴다. 학습 상태를 바꾸는 것은 Java 다 - Evidence,
 * mastery, 다음 행동은 전부 이쪽 소관이고(ADR-0011) 두 언어가 같은 테이블을 고치면
 * 어느 쪽이 정본인지 알 수 없게 된다.
 *
 * <p><b>job 하나씩 별도 트랜잭션으로 처리한다.</b> 하나로 묶으면 중간에 실패했을 때
 * 이미 반영한 것까지 되돌아가고, 다시 처리하면서 같은 일을 반복한다.
 */
@Component
public class JudgeResultPoller {

    private static final Logger log = LoggerFactory.getLogger(JudgeResultPoller.class);

    private final JudgeJobRepository jobs;
    private final JudgeResultApplier applier;
    private final int batchSize;

    public JudgeResultPoller(JudgeJobRepository jobs, JudgeResultApplier applier,
            @Value("${codesprint.judge.apply-batch-size:20}") int batchSize) {
        this.jobs = jobs;
        this.applier = applier;
        this.batchSize = batchSize;
    }

    /**
     * @return 이번에 반영한 개수. 테스트가 "돌았는가" 를 확인하는 데 쓴다.
     */
    @Scheduled(fixedDelayString = "${codesprint.judge.apply-interval-ms:1000}")
    public int applyFinishedJobs() {
        List<JudgeJobRow> finished = jobs.findUnapplied(PageRequest.of(0, batchSize));
        int applied = 0;
        for (JudgeJobRow job : finished) {
            try {
                applier.apply(job.id());
                applied++;
            } catch (RuntimeException e) {
                // 한 job 이 실패해도 나머지는 처리한다. 실패한 것은 applied_at 이
                // 비어 있으므로 다음 주기에 다시 집는다.
                log.error("job {} 의 결과를 반영하지 못했다", job.id(), e);
            }
        }
        return applied;
    }
}
