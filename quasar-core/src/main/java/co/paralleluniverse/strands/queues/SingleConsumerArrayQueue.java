/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2013-2014, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.strands.queues;

import co.paralleluniverse.common.util.UtilUnsafe;
import com.google.common.collect.Lists;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import sun.misc.Unsafe;

/**
 *
 * @author pron
 */
abstract class SingleConsumerArrayQueue<E> extends SingleConsumerQueue<E, Integer> {
    final int capacity;
    final int mask;
    volatile int p001, p002, p003, p004, p005, p006, p007;
    volatile long head; // next element to be read
    volatile long p101, p102, p103, p104, p105, p106, p107;
    volatile long tail; // next element to be written
    volatile long p201, p202, p203, p204, p205, p206, p207;
    int seed = (int) System.nanoTime();

    SingleConsumerArrayQueue(int capacity) {
        // size is a power of 2
        this.capacity = nextPowerOfTwo(capacity);
        this.mask = this.capacity - 1;
    }

    private static int nextPowerOfTwo(int v) {
        assert v >= 0;
        return 1 << (32 - Integer.numberOfLeadingZeros(v - 1));
    }

    @Override
    public int capacity() {
        return capacity;
    }

    @Override
    public boolean allowRetainPointers() {
        return false;
    }

    @Override
    public E value(Integer index) {
        return value(index.intValue());
    }

    public abstract E value(int index);

    abstract int arrayLength();

    abstract void awaitValue(long index);

    abstract void clearValue(long index);

    abstract void copyValue(int to, int from);

    long maxReadIndex() {
        return tail;
    }

    final long preEnq() {
        long t, w;
        for (;;) {
            t = tail;
            w = t - capacity; // "wrap point"
            if (head <= w)
                return -1;

            if (compareAndSetTail(t, t + 1))
                break;
            backoff();
        }
        return t;
    }

    @Override
    public void deq(Integer index) {
        deq(index.intValue());
    }

    public void deq(int index) {
        final long newHead = intToLongIndex(index) + 1;
        for (long i = head; i != newHead; i++)
            clearValue(i);
        orderedSetHead(newHead); // head = newHead; // 
    }

    abstract boolean hasNext(long lind, int iind);

    @Override
    public boolean hasNext() {
        final long h = head;
        return hasNext(h, (int) h & mask);
    }

    @Override
    @SuppressWarnings("empty-statement")
    public Integer pk() {
        final long h = head;
        final int h1 = (int) head & mask;
        if (!hasNext(h, h1))
            return null;
        return Integer.valueOf(h1);
    }

    @SuppressWarnings("empty-statement")
    public int succ(int index) {
        if (index < 0) {
            final Integer pk = pk();
            return pk != null ? pk : -1;
        }
        int n1 = (int) (index + 1) & mask;
        long n = intToLongIndex(n1);
        if (!hasNext(n, n1))
            return -1;
        return (int) n & mask;
    }

    @Override
    public Integer succ(Integer index) {
        final int s = succ(index != null ? index.intValue() : -1);
        return s >= 0 ? Integer.valueOf(s) : null;
    }

    @Override
    public Integer del(Integer index) {
        return del(index.intValue());
    }

    public int del(int index) {
        if (index == ((int) head & mask)) {
            deq(index);
            return -1;
        }

        long i = intToLongIndex(index);
        clearValue(i);
        long t = tail;
        if (i == t) {
            if (compareAndSetTail(t, t - 1))
                return (int) (i - 1) & mask;
        }

        final long h = head;
        for (; i != h; i--)
            copyValue((int) i & mask, (int) (i - 1) & mask);

        head = h + 1; // orderedSetHead(h + 1); // 
        return index;
    }

    private long intToLongIndex(int index) {
        final int ih = (int) head & mask;
        return head + (index >= ih ? index - ih : index + capacity - ih);
    }

    @Override
    public int size() {
        return (int) (tail - head);
    }

    @Override
    public List<E> snapshot() {
        final long t = tail;
        final ArrayList<E> list = new ArrayList<E>((int) (t - head));
        for (long p = tail; p != head; p--)
            list.add(value((int) p & mask));

        return Lists.reverse(list);
    }

    int next(int i) {
        return (i + 1) & mask;
    }

    int prev(int i) {
        return --i & mask;
    }

    void backoff() {
        int spins = 1 << 8;
        int r = seed;
        while (spins >= 0) {
            r ^= r << 1;
            r ^= r >>> 3;
            r ^= r << 10; // xorshift
            if (r >= 0)
                --spins;
        }
        seed = r;
    }

    @Override
    public Iterator<E> iterator() {
        return new QueueIterator();
    }

    @Override
    public void resetIterator(Iterator<E> iter) {
        ((QueueIterator) iter).n = -1;
    }

    private class QueueIterator implements Iterator<E> {
        private int n = -1;

        @Override
        public boolean hasNext() {
            return succ(n) >= 0;
        }

        @Override
        public E next() {
            n = succ(n);
            return value(n);
        }

        @Override
        public void remove() {
            n = del(n);
        }
    }
    ////////////////////////////////////////////////////////////////////////
    static final Unsafe UNSAFE = UtilUnsafe.getUnsafe();
    private static final long headOffset;
    private static final long tailOffset;

    static {
        try {
            headOffset = UNSAFE.objectFieldOffset(SingleConsumerArrayQueue.class.getDeclaredField("head"));
            tailOffset = UNSAFE.objectFieldOffset(SingleConsumerArrayQueue.class.getDeclaredField("tail"));
        } catch (Exception ex) {
            throw new Error(ex);
        }
    }

    /**
     * CAS tail field. Used only by preEnq.
     */
    private boolean compareAndSetTail(long expect, long update) {
        return UNSAFE.compareAndSwapLong(this, tailOffset, expect, update);
    }

    private void orderedSetHead(long value) {
        UNSAFE.putOrderedLong(this, headOffset, value);
    }
}
