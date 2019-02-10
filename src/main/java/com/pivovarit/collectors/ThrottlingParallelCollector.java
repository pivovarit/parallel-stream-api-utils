package com.pivovarit.collectors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static com.pivovarit.collectors.CollectorUtils.accumulatingResults;
import static com.pivovarit.collectors.CollectorUtils.mergingPartialResults;
import static com.pivovarit.collectors.CollectorUtils.supplyWithResources;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.supplyAsync;

/**
 * @author Grzegorz Piwowarek
 */
final class ThrottlingParallelCollector<T, R, C extends Collection<R>>
  implements Collector<T, List<CompletableFuture<R>>, CompletableFuture<C>>, AutoCloseable {

    private final Semaphore limiter;

    private volatile boolean isFailed = false;

    private final ParallelDispatcher<R> dispatcher;

    private final Function<T, R> operation;
    private final Supplier<C> collectionFactory;

    ThrottlingParallelCollector(
      Function<T, R> operation,
      Supplier<C> collection,
      Executor executor,
      int parallelism) {
        this(operation, collection, executor, new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>(), parallelism);
    }

    ThrottlingParallelCollector(
      Function<T, R> operation,
      Supplier<C> collection,
      Executor executor,
      Queue<Supplier<R>> workingQueue,
      Queue<CompletableFuture<R>> pendingQueue,
      int parallelism) {
        this.dispatcher = new ParallelDispatcher<>(executor, workingQueue, pendingQueue);
        this.collectionFactory = collection;
        this.operation = operation;
        this.limiter = new Semaphore(parallelism);
    }

    @Override
    public Supplier<List<CompletableFuture<R>>> supplier() {
        return ArrayList::new;
    }

    @Override
    public BinaryOperator<List<CompletableFuture<R>>> combiner() {
        return (left, right) -> {
            left.addAll(right);
            return left;
        };
    }

    @Override
    public BiConsumer<List<CompletableFuture<R>>, T> accumulator() {
        return (acc, e) -> {
            CompletableFuture<R> future = new CompletableFuture<>();
            dispatcher.pendingQueue.add(future);
            dispatcher.workingQueue.add(() -> isFailed ? null : operation.apply(e));
            acc.add(future);
        };
    }

    @Override
    public Function<List<CompletableFuture<R>>, CompletableFuture<C>> finisher() {
        if (dispatcher.workingQueue.size() != 0) {
            dispatcher.dispatcher.execute(dispatch(dispatcher.workingQueue));
            return foldLeftFutures().andThen(f -> supplyWithResources(() -> f, dispatcher.dispatcher::shutdown));
        } else {
            return supplyWithResources(() -> (__) -> completedFuture(collectionFactory
              .get()), dispatcher.dispatcher::shutdown);
        }
    }

    @Override
    public Set<Characteristics> characteristics() {
        return EnumSet.of(Characteristics.UNORDERED);
    }

    @Override
    public void close() {
        dispatcher.dispatcher.shutdown();
    }

    private Runnable dispatch(Queue<Supplier<R>> tasks) {
        return () -> {
            Supplier<R> task;
            while ((task = tasks.poll()) != null && !Thread.currentThread().isInterrupted()) {

                try {
                    limiter.acquire();
                    if (isFailed) {
                        dispatcher.pendingQueue.forEach(f -> f.cancel(true));
                        break;
                    }
                    runNext(task);
                } catch (InterruptedException e) {
                    closeAndCompleteRemaining(e);
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    closeAndCompleteRemaining(e);
                    break;
                }
            }
        };
    }

    private void runNext(Supplier<R> task) {
        supplyAsync(task, dispatcher.executor)
          .whenComplete((r, throwable) -> {
              CompletableFuture<R> next = Objects.requireNonNull(dispatcher.pendingQueue.poll());
              supplyWithResources(() -> throwable == null
                  ? next.complete(r)
                  : supplyWithResources(() -> next.completeExceptionally(throwable), () -> isFailed = true),
                limiter::release);
          });
    }

    private void closeAndCompleteRemaining(Exception e) {
        dispatcher.pendingQueue.forEach(future -> future.completeExceptionally(e));
    }

    private Function<List<CompletableFuture<R>>, CompletableFuture<C>> foldLeftFutures() {
        return futures -> futures.stream()
          .reduce(completedFuture(collectionFactory.get()),
            accumulatingResults(),
            mergingPartialResults());
    }
}
