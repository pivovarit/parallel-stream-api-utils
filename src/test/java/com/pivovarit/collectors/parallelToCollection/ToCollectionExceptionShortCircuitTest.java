package com.pivovarit.collectors.parallelToCollection;

import com.pivovarit.collectors.infrastructure.ExecutorAwareTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import static com.pivovarit.collectors.ParallelCollectors.parallelToCollection;
import static com.pivovarit.collectors.ParallelCollectors.supplier;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

public class ToCollectionExceptionShortCircuitTest extends ExecutorAwareTest {

    @Test
    void shouldCollectToCollectionAndShortCircuitOnException() {

        // given
        executor = threadPoolExecutor(1);

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            assertThatThrownBy(() -> {
                IntStream.range(0, 1000000).boxed()
                  .map(i -> supplier(() -> {
                      try {
                          Thread.sleep(100);
                      } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                      }
                      if (i != Integer.MAX_VALUE) {
                          throw new IllegalArgumentException();
                      } else {
                          return i;
                      }
                  }))
                  .collect(parallelToCollection(ArrayList::new, executor, 1))
                  .join();
            })
              .isInstanceOf(CompletionException.class)
              .hasCauseExactlyInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    void shouldCollectToCollectionAndShortCircuitOnExceptionUnbounded() {

        // given
        executor = threadPoolExecutor(1);

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            assertThatThrownBy(() -> {
                IntStream.range(0, 1000000).boxed()
                  .map(i -> supplier(() -> {
                      try {
                          Thread.sleep(100);
                      } catch (InterruptedException e) {
                          throw new IllegalStateException(e);
                      }
                      if (i != Integer.MAX_VALUE) {
                          throw new IllegalArgumentException();
                      } else {
                          return i;
                      }
                  }))
                  .collect(parallelToCollection(ArrayList::new, executor))
                  .join();
            })
              .isInstanceOf(CompletionException.class)
              .hasCauseExactlyInstanceOf(IllegalArgumentException.class);
        });
    }
}
