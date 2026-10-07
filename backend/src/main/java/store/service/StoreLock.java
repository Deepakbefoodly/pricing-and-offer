package store.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * One store-wide read/write lock. Every operation that checks state and then changes it
 * (admin edits, cart changes, checkout, coupon generation) runs under the write lock, so its
 * checks cannot be invalidated halfway through. Reads share the read lock and see a consistent view.
 * Fair ordering keeps a steady stream of reads from starving writers.
 */
@Component
public class StoreLock {

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    public <T> T read(Supplier<T> action) {
        lock.readLock().lock();
        try {
            return action.get();
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T write(Supplier<T> action) {
        lock.writeLock().lock();
        try {
            return action.get();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
