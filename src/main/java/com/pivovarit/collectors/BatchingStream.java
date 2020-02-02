package com.pivovarit.collectors;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliterator;
import static java.util.stream.Stream.empty;
import static java.util.stream.Stream.of;
import static java.util.stream.StreamSupport.stream;

/**
 * @author Grzegorz Piwowarek
 */
final class BatchingStream<T> implements Iterator<List<T>> {

    private final List<T> source;
    private final int size;

    private int chunks;
    private int chunkSize;
    private int leftElements;
    private int i;

    private BatchingStream(List<T> list, int numberOfParts) {
        source = list;
        size = list.size();
        chunks = numberOfParts;
        chunkSize = (int) Math.ceil(((double) size) / numberOfParts);
        leftElements = size;
    }

    private static <T> Iterator<List<T>> from(List<T> source, int chunks) {
        return new BatchingStream<>(source, chunks);
    }

    static <T> Stream<List<T>> partitioned(List<T> list, int numberOfParts) {
        int size = list.size();

        if (size == numberOfParts) {
            return list.stream().map(Collections::singletonList);
        } else if (size == 0) {
            return empty();
        } else if (numberOfParts == 1) {
            return of(list);
        } else {
            return stream(spliterator(from(list, numberOfParts), numberOfParts, ORDERED), false);
        }
    }

    static <T, R> Function<List<T>, List<R>> batching(Function<T, R> mapper) {
        return batch -> {
            List<R> list = new ArrayList<>(batch.size());
            for (T t : batch) {
                list.add(mapper.apply(t));
            }
            return list;
        };
    }

    @Override
    public boolean hasNext() {
        return i < size && chunks != 0;
    }

    @Override
    public List<T> next() {
        List<T> batch = source.subList(i, i + chunkSize);
        i = i + chunkSize;
        leftElements = leftElements - chunkSize;
        chunkSize = (int) Math.ceil(((double) leftElements) / --chunks);
        return batch;
    }
}
