package com.pivovarit.collectors;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * @author Grzegorz Piwowarek
 */
final class UnboundedParallelCollector<T, R, C extends Collection<R>>
  extends AbstractParallelCollector<T, R, C>
  implements AutoCloseable {

    private final ParallelDispatcher<R> dispatcher;

    private final Function<T, R> operation;
    private final Supplier<C> collectionFactory;

    UnboundedParallelCollector(
      Function<T, R> operation,
      Supplier<C> collection,
      Executor executor) {
        this(operation, collection, executor, new ConcurrentLinkedQueue<>(), new ConcurrentLinkedQueue<>());
    }

    UnboundedParallelCollector(
      Function<T, R> operation,
      Supplier<C> collection,
      Executor executor,
      Queue<Supplier<R>> workingQueue,
      Queue<CompletableFuture<R>> pendingQueue) {
        this.dispatcher = new ParallelDispatcher<>(executor, workingQueue, pendingQueue, this::dispatch);
        this.collectionFactory = collection;
        this.operation = operation;
    }

    @Override
    public BiConsumer<List<CompletableFuture<R>>, T> accumulator() {
        return (acc, e) -> {
            CompletableFuture<R> future = dispatcher.newPending();
            dispatcher.addTask(() -> dispatcher.isMarkedFailed() ? null : operation.apply(e));
            acc.add(future);
        };
    }

    @Override
    public Function<List<CompletableFuture<R>>, CompletableFuture<C>> finisher() {
        if (dispatcher.isNotEmpty()) {
            dispatcher.start();
            return foldLeftFutures(collectionFactory).andThen(f -> supplyWithResources(() -> f, dispatcher::close));
        } else {
            return supplyWithResources(() -> (__) -> completedFuture(collectionFactory.get()), dispatcher::close);
        }
    }

    @Override
    public Set<Characteristics> characteristics() {
        return EnumSet.of(Characteristics.UNORDERED);
    }

    @Override
    public void close() {
        dispatcher.close();
    }

    private Runnable dispatch(Queue<Supplier<R>> tasks) {
        return () -> {
            Supplier<R> task;
            while ((task = tasks.poll()) != null && !Thread.currentThread().isInterrupted()) {

                try {
                    if (dispatcher.isMarkedFailed()) {
                        dispatcher.cancelAll();
                        break;
                    }
                    runNext(task);
                } catch (Exception e) {
                    closeAndCompleteRemaining(e);
                    break;
                }
            }
        };
    }

    private void runNext(Supplier<R> task) {
        dispatcher.supply(task)
          .whenComplete((r, throwable) -> {
              CompletableFuture<R> next = Objects.requireNonNull(dispatcher.nextPending());
              if (throwable == null) {
                  next.complete(r);
              } else {
                  next.completeExceptionally(throwable);
                  dispatcher.markFailed();
              }
          });
    }

    private void closeAndCompleteRemaining(Exception e) {
        dispatcher.closeExceptionally(e);
    }
}
