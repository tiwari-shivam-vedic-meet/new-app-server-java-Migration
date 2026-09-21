package com.vedicmeet.appserver.user;

import com.vedicmeet.appserver.config.AppConstants.Collections;
import com.vedicmeet.appserver.crypto.CryptoUtil;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Faithful port of Node UserService.myAvailableBalance (utils/classes/user.js),
 * served by GET /user/available_balance (the /user module is user-only:
 * userAuthMiddleware).
 *
 * Notable: the wallet's `coins` field is AES-encrypted at rest (crypto-js), so this
 * exercises CryptoUtil field-decryption (not just reqData). Node then parseFloat()s
 * the decrypted value.
 *
 * Faithful quirk: the Node route calls myAvailableBalance(req.body, req.user) with no
 * userType, so the branch uses input.userId (from the body). For a GET that is absent,
 * so the wallet lookup usually finds nothing and returns 0 — preserved exactly.
 */
@Service
public class UserProfileService {

    private final MongoTemplate mongo;
    private final CryptoUtil cryptoUtil;

    public UserProfileService(MongoTemplate mongo, CryptoUtil cryptoUtil) {
        this.mongo = mongo;
        this.cryptoUtil = cryptoUtil;
    }

    // ---- Prompt C write: PUT /v2/user/update (utils/classes/user.js updateUser L4161). Diff-pending. ----

    /**
     * updateUser(input, userObj, files) — profile field-merge → {@code findOneAndUpdate({new:true})}.
     * The only external dependency is the S3 upload of {@code files.profileImage}, passed in here as an
     * already-resolved URL (null when no file / reqData path). Everything else is a straight $set.
     *
     * Node quirks preserved:
     *   - fields are written only when truthy AND not the literal string "null".
     *   - the email-uniqueness query uses {@code {_id:{$nin: userObj._id}}} — Mongoose wraps the scalar
     *     into an array, so it means "any OTHER user"; reproduced as {@code $nin:[userId]}.
     *   - `details` is replaced with the merge {@code {...userObj.details, ...newDetails}} (existing details
     *     preserved, new keys overlaid).
     *   - streak increments off the freshly-read `extingUser.streak`.
     */
    public Document updateUser(Map<String, Object> input, Document userObj, String uploadedProfileImageUrl) {
        ObjectId userId = userObj.getObjectId("_id");
        Document extingUser = mongo.getCollection(Collections.USERS).find(new Document("_id", userId)).first();

        Object email = input.get("email");
        if (usable(email)) {
            Document emailExist = mongo.getCollection(Collections.USERS).find(
                    new Document("_id", new Document("$nin", Arrays.asList(userId)))
                            .append("email", email).append("isDeleted", false)).first();
            if (emailExist != null) throw new RuntimeException("This Email is already in use");
        }

        List<String> requiredFields = Arrays.asList("name", "email", "phone", "gender", "dob",
                "timeOfBirth", "placeOfBirth", "country", "state", "city", "pincode", "address");
        boolean isProfileComplete = true;
        for (String f : requiredFields) {
            Object v = input.get(f);
            if (!(v instanceof String) || ((String) v).trim().isEmpty()) { isProfileComplete = false; break; }
        }

        Document toUpdate = new Document();
        if (isProfileComplete) toUpdate.put("registrationComplete", true);

        Document details = new Document();
        if (usable(input.get("timeOfBirth"))) details.put("timeOfBirth", input.get("timeOfBirth"));
        if (usable(input.get("placeOfBirth"))) details.put("placeOfBirth", input.get("placeOfBirth"));
        if (usable(input.get("problems"))) details.put("problems", input.get("problems"));
        if (usable(input.get("address"))) details.put("address", input.get("address"));
        if (usable(input.get("city"))) details.put("city", input.get("city"));
        if (usable(input.get("state"))) details.put("state", input.get("state"));
        if (usable(input.get("country"))) details.put("country", input.get("country"));
        if (usable(input.get("pincode"))) details.put("zip", input.get("pincode"));
        if (usable(input.get("email"))) details.put("email", input.get("email"));
        if (usable(input.get("name"))) {
            toUpdate.put("name", input.get("name"));
            details.put("name", input.get("name"));
        }
        if (usable(input.get("gender"))) details.put("gender", String.valueOf(input.get("gender")).toLowerCase());

        if (uploadedProfileImageUrl != null) toUpdate.put("profileImage", uploadedProfileImageUrl);

        if (usable(input.get("streak"))) {
            toUpdate.put("todayStreak", true);
            int cur = extingUser == null ? 0 : intVal(extingUser.get("streak"));
            toUpdate.put("streak", cur + 1);
        }

        Document existingDetails = userObj.get("details") instanceof Document
                ? (Document) userObj.get("details") : new Document();
        Document mergedDetails = new Document();
        mergedDetails.putAll(existingDetails);
        mergedDetails.putAll(details);

        Document set = new Document(toUpdate);
        set.put("details", mergedDetails);

        return mongo.getCollection(Collections.USERS).findOneAndUpdate(
                new Document("_id", userId),
                new Document("$set", set),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
    }

    /** Node truthiness for these string fields: present, non-empty, and not the literal "null". */
    private boolean usable(Object v) {
        if (v == null) return false;
        if (v instanceof String) {
            String s = (String) v;
            return !s.isEmpty() && !s.equals("null");
        }
        return true;
    }

    private int intVal(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? 0 : Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return 0; }
    }

    public Map<String, Object> myAvailableBalance(Object bodyUserId, Document user, String userType) {
        Object userId = "user".equals(userType) ? user.getObjectId("_id") : bodyUserId;

        Document wallet = mongo.getCollection(Collections.WALLETS)
                .find(new Document("userId", userId)).first();

        double availableBalance = 0;
        if (wallet != null && wallet.get("coins") != null) {
            String decrypted = cryptoUtil.decrypt(wallet.get("coins").toString());
            try {
                availableBalance = Double.parseDouble(decrypted.replace("\"", "").trim());
            } catch (NumberFormatException e) {
                availableBalance = 0;
            }
        }
        Object savingBalance = (wallet != null && wallet.get("savedAmount") != null)
                ? wallet.get("savedAmount") : 0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("availableBalance", availableBalance);
        result.put("savingBalance", savingBalance);
        return result;
    }
}
