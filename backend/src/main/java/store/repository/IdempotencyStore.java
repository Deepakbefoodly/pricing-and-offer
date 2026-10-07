package store.repository;

import org.springframework.stereotype.Repository;
import store.domain.IdempotencyRecord;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Successful checkout requests by Idempotency-Key. Keys are global (not per cart) and kept for the life of
 * the process; a production store would expire them after a retention window.
 */
@Repository
public class IdempotencyStore {

    private final Map<String, IdempotencyRecord> records = new ConcurrentHashMap<>();

    public Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(records.get(key));
    }

    public void add(IdempotencyRecord record) {
        if (records.putIfAbsent(record.key(), record) != null) {
            throw new IllegalStateException("Idempotency key already recorded: " + record.key());
        }
    }
}
