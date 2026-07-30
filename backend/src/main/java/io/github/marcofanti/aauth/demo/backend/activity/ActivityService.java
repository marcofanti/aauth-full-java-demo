package io.github.marcofanti.aauth.demo.backend.activity;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.stereotype.Service;

/** In-memory, bounded activity feed. */
@Service
public class ActivityService {

    private static final int MAX_ENTRIES = 200;

    private final Deque<ActivityEntry> entries = new ArrayDeque<>();

    public synchronized void record(String agent, String message) {
        if (entries.size() == MAX_ENTRIES) {
            entries.removeLast();
        }
        entries.addFirst(new ActivityEntry(Instant.now(), agent, message));
    }

    /** Newest first. */
    public synchronized List<ActivityEntry> list(int limit) {
        List<ActivityEntry> snapshot = new ArrayList<>();
        for (ActivityEntry entry : entries) {
            if (snapshot.size() == limit) {
                break;
            }
            snapshot.add(entry);
        }
        return snapshot;
    }

    public synchronized void clear() {
        entries.clear();
    }
}
