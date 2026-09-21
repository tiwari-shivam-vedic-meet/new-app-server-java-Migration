package com.vedicmeet.appserver.admin;

import com.vedicmeet.appserver.config.AppConstants;
import com.vedicmeet.appserver.config.AppConstants.Collections;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin consultant-payout module. Port of utils/classes/payout-service.js (routed by
 * rest-apis/modules/admin/payout.js). The two read paths (list, listForSpecificMonth) are ported
 * faithfully. The Excel export and the settlement upload are file-transport / money seams — see the
 * per-method comments. Money movement (uploadExcelWithSettlement) is DOUBLE-gated and NOT ported.
 */
@Service
public class AdminPayoutService {

    private static final Pattern LEADING_INT = Pattern.compile("^[+-]?\\d+");

    private final MongoTemplate mongo;
    @SuppressWarnings("unused")
    private final AdminMongoSupport support;
    private final AppConstants constants;
    private final boolean payoutExecutionEnabled;

    public AdminPayoutService(MongoTemplate mongo, AdminMongoSupport support, AppConstants constants,
                              @Value("${vedicmeet.payout.execution-enabled:false}") boolean payoutExecutionEnabled) {
        this.mongo = mongo;
        this.support = support;
        this.constants = constants;
        this.payoutExecutionEnabled = payoutExecutionEnabled;
    }

    /**
     * SEAM(file-transport): payout.js:8-24 -> PayoutService.downloadExel(query) writes an .xlsx to disk and
     * streams it via res.download(). AdminPayoutController returns an empty CSV (file streaming is not wired in
     * this migration), so the generated file is unobservable. The sheet-building reads are intentionally not
     * re-run here (unobservable + money-adjacent). Documented seam for the manual transfer.
     */
    public Map<String, Object> export(Map<String, String> query) {
        return new LinkedHashMap<>();
    }

    /** SEAM(file-transport): payout.js:27-42 -> PayoutService.downloadExcelWithPeriod(query). Same seam as export(). */
    public Map<String, Object> exportWithPeriod(Map<String, String> query) {
        return new LinkedHashMap<>();
    }

    /**
     * MONEY / HUMAN-REVIEW REQUIRED — payout.js:45-63 -> PayoutService.uploadExcelWithSettlement(req.body, req.files)
     * parses an uploaded settlement spreadsheet and writes payout + wallet records (moves money). Double-gated:
     * (1) {@code @MigrationWrite} on the controller (MIGRATION_WRITES_ENABLED=false blocks the route before this
     * runs) and (2) {@code vedicmeet.payout.execution-enabled} (PAYOUT_EXECUTION_ENABLED=false) below. The
     * multipart file (req.files) is also not wired in the Java controller (@RequestBody JSON only), so the
     * settlement source is unavailable. Do NOT implement fund movement without human sign-off.
     */
    public Map<String, Object> uploadWithSettlement(Map<String, Object> body) {
        if (!payoutExecutionEnabled) {
            throw new IllegalStateException("Payout settlement execution is disabled pending human review");
        }
        throw new UnsupportedOperationException(
                "Payout settlement not ported: requires multipart file transport and human sign-off before moving money");
    }

    public Map<String, Object> list(Map<String, String> query) {
        Map<String, String> input = query == null ? Map.of() : query;
        int page = jsInt(input.get("page"), 1);
        int limit = jsInt(input.get("limit"), 1000);
        int skipIndex = (page - 1) * limit;
        String search = input.getOrDefault("search", "");

        Document params = new Document("status", true).append("isDeleted", false);
        if (search != null && !search.isEmpty()) {
            // FAITHFUL(node-quirk): payout-service.js:316-320 sets BOTH name and userName to the same unescaped regex.
            params.append("name", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
            params.append("userName", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> pipeline = new ArrayList<>();
        pipeline.add(new Document("$match", params));
        pipeline.add(Document.parse("{ $lookup: { from: 'wallet_transactions', let: { consultantId: '$_id' }, pipeline: [ { $match: { $expr: { $eq: [ '$$consultantId', '$consultantId' ] }, userType: 'cons', isConsTransfer: false } }, { $group: { _id: null, totalCredits: { $sum: { $cond: [ { $eq: [ '$transactionType', 0 ] }, { $toDouble: '$coins' }, 0 ] } }, totalDebits: { $sum: { $cond: [ { $eq: [ '$transactionType', 1 ] }, { $toDouble: '$coins' }, 0 ] } } } } ], as: 'consWallet' } }"));
        pipeline.add(Document.parse("{ $lookup: { from: 'banks', let: { consultantId: '$_id' }, pipeline: [ { $match: { $expr: { $and: [ { $eq: [ '$$consultantId', '$consultantId' ] }, { $eq: [ '$documentType', 'PANCARD' ] } ] } } } ], as: 'consPancard' } }"));
        pipeline.add(Document.parse("{ $lookup: { from: 'banks', let: { consultantId: '$_id' }, pipeline: [ { $match: { $expr: { $and: [ { $eq: [ '$$consultantId', '$consultantId' ] }, { $eq: [ '$documentType', 'BANK' ] } ] } } } ], as: 'consBank' } }"));
        pipeline.add(Document.parse("{ $unwind: '$consWallet' }"));
        pipeline.add(Document.parse("{ $unwind: { path: '$consPancard', preserveNullAndEmptyArrays: true } }"));
        pipeline.add(Document.parse("{ $unwind: { path: '$consBank', preserveNullAndEmptyArrays: true } }"));
        pipeline.add(Document.parse("{ $addFields: { name: '$accountName', email: '$details.email', mobile: '$details.phone', countryCode: '$details.phonePrefix', createdAt: '$created_at' } }"));
        pipeline.add(Document.parse("{ $project: { _id: 1, name: 1, email: 1, createdAt: 1, consWallet: 1, countryCode: 1, wallet: 1, mobile: 1, panNumber: '$consPancard.panNumber', accountNumber: '$consBank.accountNumber', bankName: '$consBank.bankName', ifsc: '$consBank.ifsc', bankHolderName: '$consBank.bankHolderName' } }"));
        pipeline.add(new Document("$facet", new Document("list", List.of(
                new Document("$sort", new Document("createdAt", -1)),
                new Document("$skip", skipIndex),
                new Document("$limit", limit)))
                .append("count", List.of(new Document("$count", "total")))));

        List<Document> aggregated = mongo.getCollection(Collections.CONSULTANTS).aggregate(pipeline).into(new ArrayList<>());

        // FAITHFUL: payout-service.js:509 destructures the single $facet document ([{ list = [], count = [] }]).
        List<Document> rows = new ArrayList<>();
        List<Document> count = new ArrayList<>();
        if (!aggregated.isEmpty()) {
            Document facet = aggregated.get(0);
            List<Document> facetList = facet.getList("list", Document.class);
            List<Document> facetCount = facet.getList("count", Document.class);
            if (facetList != null) rows = facetList;
            if (facetCount != null) count = facetCount;
        }
        int total = 0;
        if (!count.isEmpty()) {
            total = ((Number) count.get(0).get("total")).intValue();
        }

        List<Document> finalList = new ArrayList<>();
        for (Document data : rows) {
            Document wallet = (Document) data.get("consWallet");
            double totalCredits = number(wallet == null ? null : wallet.get("totalCredits"));
            double totalDebits = number(wallet == null ? null : wallet.get("totalDebits"));
            double availableBalance = totalCredits - totalDebits;
            data.put("coins", getThePayableAmount(availableBalance));
            finalList.add(data);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("finalList", finalList);
        result.put("total", total);
        return result;
    }

    /**
     * FAITHFUL: payout-service.js:24-47 getThePayableAmount. Reads master.payment.PG/TDS (defaults 2.5/10 when
     * falsy) and applies PG then TDS, re-rounding each intermediate to 2 decimals (JS toFixed) before subtracting.
     * PARITY-RISK(node-quirk): JS Number.prototype.toFixed has float-boundary rounding (e.g. (1.005).toFixed(2)
     * === "1.00"); this uses BigDecimal HALF_UP, which can differ on exact .xx5 boundaries. Returns a String,
     * exactly like Node.
     */
    private String getThePayableAmount(double availableBalance) {
        Document master = mongo.getCollection(Collections.MASTERS).find(new Document()).first();
        Document payment = master == null ? null : (Document) master.get("payment");
        double pgCharge = payment != null && truthy(payment.get("PG")) ? number(payment.get("PG")) : 2.5;
        double tdsCharge = payment != null && truthy(payment.get("TDS")) ? number(payment.get("TDS")) : 10;

        double paymentGatewayAmount = round2(availableBalance * pgCharge / 100.0);
        double subtotal = availableBalance - paymentGatewayAmount;
        double tdsChargeAmount = round2(subtotal * tdsCharge / 100.0);
        double payableAmount = subtotal - tdsChargeAmount;
        return toFixed2(payableAmount);
    }

    public Map<String, Object> specificMonth(Map<String, String> query) {
        return listForSpecificMonth(query);
    }

    Map<String, Object> listForSpecificMonth(Map<String, String> query) {
        Map<String, String> input = query == null ? Map.of() : query;
        String search = input.getOrDefault("search", "");
        String month = input.get("month");
        String year = input.get("year");

        Document match = new Document("month", month).append("year", year);
        if (search != null && !search.isEmpty()) {
            // FAITHFUL(node-quirk): payout-service.js:540-542 adds an unescaped name regex to the $match.
            match.append("name", new Document("$regex", ".*" + search + ".*").append("$options", "i"));
        }

        List<Document> consultants = mongo.getCollection(Collections.CONSULTANT_PAYOUT_MONTH_ENDS)
                .aggregate(List.of(new Document("$match", match))).into(new ArrayList<>());

        Document prevPayoutTrack = mongo.getCollection(Collections.CONSULTANT_PAYOUT_TRACKS)
                .find(new Document("month", month).append("year", year)).first();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("finalList", consultants);
        result.put("total", consultants.size());
        if (prevPayoutTrack == null) {
            result.put("prevPayoutTrack", null);
        } else {
            // FAITHFUL(node-quirk): payout-service.js:558 spreads track.toJSON() then overrides
            // payoutSheet = MEDIA_URL + track.payoutSheet (yields "<MEDIA_URL>undefined" when the field is absent).
            Object sheet = prevPayoutTrack.get("payoutSheet");
            String payoutSheet = constants.mediaUrl + (sheet == null ? "undefined" : String.valueOf(sheet));
            Document track = new Document(prevPayoutTrack);
            track.put("payoutSheet", payoutSheet);
            result.put("prevPayoutTrack", track);
        }
        return result;
    }

    private static int jsInt(String value, int def) {
        if (value == null) return def;
        Matcher m = LEADING_INT.matcher(value.trim());
        if (!m.find()) throw new IllegalArgumentException("NaN");
        return Integer.parseInt(m.group());
    }

    private static double number(Object value) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return 0.0;
        try { return Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private static boolean truthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) { double d = n.doubleValue(); return d != 0 && !Double.isNaN(d); }
        return !String.valueOf(value).isEmpty();
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String toFixed2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
