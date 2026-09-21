package com.vedicmeet.appserver.call;

import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Java implementation of Node {@code CallManager.handleCallTimeout}. The single-winner database
 * transition is delegated to {@link CallCompletionStore#claimAndApply}; retries and simultaneous
 * end events therefore return DUPLICATE without charging twice.
 */
@Service
public class CallCompletionService {

    public enum Outcome { COMPLETED, DUPLICATE, REDIS_NOT_ACCEPTED, NOT_FOUND }

    public record Result(Outcome outcome, CallCompletionStore.Amounts amounts,
                         double actualUserDeduction) {}

    private final CallCompletionStore store;
    private final CallTimeoutSettlementService calculator;

    public CallCompletionService(CallCompletionStore store, CallTimeoutSettlementService calculator) {
        this.store = store;
        this.calculator = calculator;
    }

    public Result complete(String roomId, String userId, String consultantId,
                           String callStatus, String callEndedBy) {
        Map<String, String> call = store.getCallHash(roomId);
        if (call == null || !"accepted".equals(call.get("status"))) {
            return new Result(Outcome.REDIS_NOT_ACCEPTED, null, 0);
        }

        Document waitlist = store.findWaitlist(roomId);
        if (waitlist == null) return new Result(Outcome.NOT_FOUND, null, 0);
        if (!"progress".equals(waitlist.getString("status"))) {
            return new Result(Outcome.DUPLICATE, null, 0);
        }

        String resolvedUserId = value(userId, call.get("userId"), waitlist.get("user_id"));
        String resolvedConsultantId = value(consultantId, call.get("consultantId"), waitlist.get("consultant_id"));
        Document user = store.findUser(resolvedUserId);
        Document consultant = store.findConsultant(resolvedConsultantId);
        if (user == null || consultant == null) return new Result(Outcome.NOT_FOUND, null, 0);

        double durationSeconds = elapsedSeconds(waitlist);
        CallTimeoutSettlementService.Input input = settlementInput(waitlist, consultant, resolvedConsultantId,
                durationSeconds);
        CallTimeoutSettlementService.CallTimeoutSettlement calculated = calculator.compute(input);
        CallCompletionStore.CompletionPolicy policy = store.evaluateCompletionPolicy(
                resolvedConsultantId, callEndedBy, waitlist);
        CallCompletionStore.Amounts amounts = new CallCompletionStore.Amounts(
                calculated.baseAmount, calculated.platformAmount,
                policy.blockConsultantPayout() ? 0 : calculated.consultantWallet,
                calculated.userWallet, calculated.extraDuration, calculated.extraDurationAmount,
                calculated.typeOfSession, durationSeconds);

        CallCompletionStore.ApplyResult applied = store.claimAndApply(roomId, resolvedUserId,
                resolvedConsultantId, callStatus == null ? "completed" : callStatus,
                callEndedBy == null ? "unknown" : callEndedBy, amounts, waitlist, user, consultant,
                policy);
        if (!applied.claimed()) return new Result(Outcome.DUPLICATE, amounts, 0);

        // The money transaction has committed. Everything below is idempotent/best-effort runtime
        // cleanup or an outbox write; a notification outage cannot roll back a completed call.
        store.clearRuntime(roomId, resolvedConsultantId);
        Document completed = applied.completedWaitlist() == null ? waitlist : applied.completedWaitlist();
        store.emitLeaveRooms(resolvedUserId, resolvedConsultantId, completed, callEndedBy,
                policy.showFirstConsultationAgain());
        store.notifyCallEnded(resolvedUserId, resolvedConsultantId, user, consultant, completed,
                amounts, applied.actualUserDeduction());
        store.deleteCallInitiated(roomId, resolvedUserId, resolvedConsultantId);
        store.enqueuePostCallWork(resolvedUserId, resolvedConsultantId, completed, amounts,
                applied.actualUserDeduction(), callEndedBy);
        store.callNextWaitingUser(consultant, roomId, callEndedBy);
        return new Result(Outcome.COMPLETED, amounts, applied.actualUserDeduction());
    }

    private CallTimeoutSettlementService.Input settlementInput(Document waitlist, Document consultant,
                                                                 String consultantId, double durationSeconds) {
        Document session = doc(waitlist.get("session_info"));
        Document price = doc(consultant.get("price"));
        Document coupon = doc(waitlist.get("coupon"));

        CallTimeoutSettlementService.Input in = new CallTimeoutSettlementService.Input();
        in.callDurationInSeconds = durationSeconds;
        in.sessionPrice = number(session.get("price"));
        in.basePrice = number(session.get("basePrice"));
        in.isBoosted = Boolean.TRUE.equals(session.get("isBoosted"));
        in.offerOnConsultant = Boolean.TRUE.equals(waitlist.get("offerOnConsultant"));
        in.platformShare = number(price.get("platformShare"));
        in.isSession = "session".equals(waitlist.getString("used_for"));

        Object variantId = coupon.get("variantId");
        in.hasVariant = variantId != null;
        in.couponType = coupon.getString("type");
        if (variantId != null) {
            Document offer = store.findOfferVariant(variantId);
            if (offer != null) {
                in.offerDuration = number(offer.get("duration"));
                in.offerPrice = number(offer.get("price"));
            }
        }

        Object couponId = coupon.get("couponId");
        if (couponId != null) {
            Document v2 = store.findV2Coupon(couponId);
            if (v2 != null && "CONSULTANT".equals(v2.getString("type"))
                    && "CONSULTATION".equals(v2.getString("appliesOn"))
                    && consultantId.equals(String.valueOf(v2.get("consultantId")))
                    && number(v2.get("consultantShare")) != 0) {
                in.consultantShareOverride = number(v2.get("consultantShare"));
            }
        }
        return in;
    }

    private double elapsedSeconds(Document waitlist) {
        Document timeLap = doc(waitlist.get("timeLap"));
        java.util.Date start = timeLap.getDate("startTime");
        return start == null ? 0 : Math.max(0, (System.currentTimeMillis() - start.getTime()) / 1000.0);
    }

    private Document doc(Object value) {
        return value instanceof Document d ? d : new Document();
    }

    private double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private String value(String preferred, String fallback, Object last) {
        if (preferred != null && !preferred.isBlank()) return preferred;
        if (fallback != null && !fallback.isBlank()) return fallback;
        return last == null ? null : String.valueOf(last);
    }
}
