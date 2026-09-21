package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.List;

/**
 * Port seam for {@link CallQueueService}. Executes the Node waitlist aggregation
 * ({@code $match status:'waiting', used_for:'private_call', session_info.mode ∈ activeModes} →
 * {@code $sort priority:-1, createdAt:1} → {@code $group entries:$push $$ROOT}) and returns the sorted
 * entries so the service can pick the first one.
 */
public interface CallQueueStore {

    List<Document> findWaitingPrivateCalls(String consultantId, List<String> activeModes);
}
