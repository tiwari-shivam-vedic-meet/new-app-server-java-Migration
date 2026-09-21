package com.vedicmeet.appserver.call;

import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * ⚠ SHADOW-ONLY. FAITHFUL port of Node {@code businessLogics.sendCallNotificationToNextUser}
 * (utils/classes/business-logics.js L341-390) — the waitlist queue-advance that {@code handleCallTimeout}
 * uses to ring the next waiting user once a consultant is free. Plain class, unwired.
 *
 * <p>Exactly as Node: only if the consultant has a live mode; the active modes are
 * {@code [chat|audio|video]} filtered by the {@code isChatLive/isVoiceLive/isVideoLive} flags; the
 * waitlist is matched on {@code status:'waiting', used_for:'private_call', session_info.mode ∈ activeModes},
 * sorted {@code priority desc, createdAt asc}, and the first entry becomes the next caller. Any error → null.</p>
 */
public class CallQueueService {

    public static final class NextCaller {
        public String callBy;
        public String userId;
        public String consultantId;
        public String roomId;
        public String callMode;
    }

    private final CallQueueStore store;

    public CallQueueService(CallQueueStore store) {
        this.store = store;
    }

    public NextCaller sendCallNotificationToNextUser(Document cons) {
        try {
            Document ss = cons.get("sessionsStatus") instanceof Document ? (Document) cons.get("sessionsStatus") : null;
            boolean chat = ss != null && Boolean.TRUE.equals(ss.get("isChatLive"));
            boolean voice = ss != null && Boolean.TRUE.equals(ss.get("isVoiceLive"));
            boolean video = ss != null && Boolean.TRUE.equals(ss.get("isVideoLive"));
            if (!(chat || voice || video)) {
                return null;
            }

            List<String> activeModes = new ArrayList<>();
            if (chat) activeModes.add("chat");
            if (voice) activeModes.add("audio");
            if (video) activeModes.add("video");

            String consultantId = String.valueOf(cons.get("_id"));
            List<Document> entries = store.findWaitingPrivateCalls(consultantId, activeModes);
            if (entries != null && !entries.isEmpty()) {
                Document first = entries.get(0);
                Document sessionInfo = first.get("session_info") instanceof Document
                        ? (Document) first.get("session_info") : new Document();
                NextCaller next = new NextCaller();
                next.callBy = "cons";
                next.userId = str(first.get("user_id"));
                next.consultantId = consultantId;
                next.roomId = String.valueOf(first.get("_id"));
                next.callMode = str(sessionInfo.get("mode"));
                return next;
            }
            return null;
        } catch (RuntimeException e) {
            return null; // Node: catch → return null
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
