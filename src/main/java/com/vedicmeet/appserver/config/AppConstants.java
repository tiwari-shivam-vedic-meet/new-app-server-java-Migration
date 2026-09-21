package com.vedicmeet.appserver.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mirrors the Node _constant.js values the read modules depend on.
 *
 * MEDIA_URL is derived the same way Node derives AWS_CREDS.MEDIA_URL:
 *   https://{AWS_S3_BUCKET}.s3.{AWS_REGION}.amazonaws.com/
 * TAG_REWARDS_BANNER is a hard-coded literal in Node (kept identical here).
 *
 * COLLECTION NAMES are the Mongoose-derived collection names. Mongoose pluralizes
 * the model name; most are unambiguous, but two are worth confirming against the
 * live DB (`db.getCollectionNames()` on the TEST server) before go-live — noted
 * inline. The contract-test harness will surface any mismatch as empty results.
 */
@Component
public class AppConstants {

    public final String mediaUrl;
    public final String tagRewardsBanner =
            "https://ride-chef-dev.s3.ap-south-1.amazonaws.com/Vedic_Meet/chat/1720614030940.png";

    public AppConstants(
            @Value("${AWS_S3_BUCKET:vedic-meet-bucket}") String bucket,
            @Value("${AWS_REGION:ap-south-1}") String region) {
        this.mediaUrl = "https://" + bucket + ".s3." + region + ".amazonaws.com/";
    }

    // --- Mongo collection names (Mongoose model registrations) ---
    public static final class Collections {
        public static final String CATEGORIES = "categories";              // model 'categories'
        public static final String CATEGORIES_MUSIC = "categories_musics"; // model 'categories_music' -> Mongoose pluralized (VERIFY vs DB)
        public static final String MUSICS = "musics";                      // model 'musics'
        public static final String MEDITATION_CATEGORY = "meditation-categories"; // model 'meditation-category' -> pluralized (confirmed vs Node model)
        public static final String MEDITATION_MEDIA = "meditation_medias"; // model 'meditation_media' -> Mongoose pluralized (confirmed: Node $lookup uses "meditation_medias")
        public static final String SKILLS = "skills";                      // model 'skills'
        public static final String LANGUAGES = "languages";                // model 'languages'
        public static final String FAQS = "faqs";                          // model 'faq' -> Mongoose pluralized
        public static final String GIFTS = "gifts";                        // model 'gifts'
        public static final String MEMBERSHIP_DISCOUNTS = "membership_discounts"; // model 'membership_discounts'
        public static final String COMMUNITIES = "communities";            // model 'communities'
        public static final String MASTERS = "masters";                    // model 'masters'
        public static final String USERS = "users";                        // model 'user'
        public static final String CMS = "cms";                            // VERIFY vs DB: model 'cms' (mongoose pluralization of an -s word is ambiguous)
        public static final String GALLERIES = "galleries";                // model 'gallery' -> pluralized (confirmed vs Node model)
        public static final String CONSULTANTS = "consultants";            // model 'consultant'
        public static final String EXPLORES = "explores";                  // model 'explore' -> pluralized (confirmed vs Node model)
        public static final String COMMENTS = "comments";                  // model 'comments'
        // --- banner (Phase-2) collections ---
        public static final String BANNERS = "banners";                    // model 'banner' -> pluralized (confirmed vs Node model)
        public static final String NOTICE_BOARDS = "notice_boards";        // model 'notice_boards'
        public static final String WARNINGS = "warnings";                  // model 'warnings'
        public static final String CONSULTANT_BOOSTS = "consultant_boosts";// model 'consultant_boosts'
        public static final String STATS = "stats";                        // model 'stats'
        public static final String SEED_MASTERS = "seed_masters";          // model 'seed_master' -> pluralized (confirmed vs Node model)
        public static final String FEEDBACKS = "feedbacks";                // model 'feedbacks'
        public static final String HOROSCOPES = "horoscopes";              // model 'horoscopes'
        public static final String USER_INTAKE_FORMS = "user_intake_forms";// model 'user_intake_forms'
        public static final String MUSIC_MEDIA = "music_medias";            // model 'music_media'
        public static final String MEDIA_RECENTS = "media_recents";         // model 'media_recent'
        public static final String MEDIA_FAVORITES = "media_favorites";     // model 'media_favorite'
        public static final String USER_MUSIC_MOODS = "user_music_moods";   // model 'user_music_mood'
        public static final String GROWTH_TRAINING_CATEGORIES = "growth_training_categories";
        public static final String GROWTH_TRAINING_MEDIA = "growth_training_medias";
        public static final String USER_KUNDALIS = "user-kundalis";         // model 'user-kundali'
        public static final String MATCH_MAKINGS = "match_makings";         // model 'match_makings'
        public static final String VASTU_COMPASS_CATEGORIES = "vastu_compass_categories";
        public static final String VASTU_COMPASS_ZONES = "vastu_compass_zones";
        public static final String USER_VASTU_COMPASS_RECORDS = "user-vastu-compass-records";
        public static final String TASK_REMEDIES = "task_remedies";
        public static final String TASKS = "tasks";
        public static final String ORDER_REMEDY_NOTES = "order_remedy_notes";
        public static final String AREA_OF_CONCERNS = "area_of_concerns";
        public static final String AREA_OF_CONCERN_REMEDIES = "area_of_concern_remedies";
        public static final String ANALYTICS_LOGS = "analytics_logs";
        public static final String CLICK_TRACKINGS = "clicktrackings";
        public static final String ENTRY_TRACKINGS = "entry_trackings"; // model 'entry_tracking' -> Mongoose pluralized
        public static final String ZODIAC_MESSAGES = "zodiacmessages";
        public static final String REVIEW_AND_RATINGS = "review_and_ratings"; // model 'review_and_ratings'
        public static final String COUPONS = "coupons";                    // model 'coupons'
        public static final String COUPON_MASTERS = "coupon_masters";      // model 'coupon_master'
        public static final String CONSULTANT_FORM_REQUESTS = "consultantformrequests"; // model 'consultantformrequests'
        public static final String WAITLISTS = "waitlists";                // model 'waitlists'
        // --- week-3 collections ---
        public static final String NOTIFICATIONS = "notifications";        // VERIFY vs DB: model 'notifications' (a typo'd model 'notificationns' also exists - see NOTIFICATION_RECORDS)
        public static final String SUPPORT_MASTERS = "support_masters";    // model 'support_masters'
        public static final String WALLETS = "wallets";                    // model 'wallets'
        // --- consultant-discovery (Phase-2.1) collections ---
        public static final String WALLET_TRANSACTIONS = "wallet_transactions"; // model 'wallet_transactions'
        public static final String BROADCASTS = "broadcasts";              // model 'broadcasts'
        public static final String USER_CONS_RELS = "user_cons_rels";      // model 'user_cons_rels'
        public static final String FOLLOWS = "follows";                    // model 'follows'
        public static final String REVIEW_REPLIES = "review_replies";      // model 'review_reply' (pluralized)
        public static final String CONSULTANT_SLOTS_BOOKS = "consultant_slots_books"; // model 'consultant_slots_book'
        public static final String NOTIFICATION_RECORDS = "notificationns"; // model 'notificationns' (support writes use this, NOT 'notifications')
        public static final String SCHEDULED_NOTIFICATIONS = "shedulednotifications"; // model 'shedulednotifications' (sic - misspelled in Node; already plural, Mongoose keeps as-is)
        public static final String NOTIFICATION_MESSAGES = "notificationmessages"; // model 'NotificationMessage' (Mongoose pluralized -> 'notificationmessages')
        public static final String CUSTOMER_SUPPORT_QUERIES = "customer_support_queries"; // model 'customer_support_queries'
        public static final String CUSTOMER_SUPPORT_CHATS = "customer_support_chats"; // model 'customer_support_chats'
        public static final String ADMINS = "admins";                      // model 'admin'
        public static final String TRANSACTIONS = "transactions";          // model 'transactions'
        public static final String SAVE_INITIAL_PAYMENTS = "save-initial-payments"; // model 'save-initial-payments'
        public static final String PAYMENT_WEBHOOKS = "paymentwebhooks";    // model 'paymentwebhooks' (webhook audit)
        // --- call-runtime collections (Node call.js / timer-queue.js) ---
        public static final String CALL_INITIATED = "callinitateds";        // model 'callInitated' (Mongoose pluralized)
        public static final String TEMPORARY_LOGS = "temporary_logs";       // model 'temporary_logs'
        public static final String FIXED_SESSION_WAITLISTS = "users_fixed_sessions_waitlists";
        public static final String OFFER_VARIANTS = "offer_variants";       // model 'offer_variant'
        public static final String OFFER_RULES = "offer_rules";             // model 'offer_rule'
        public static final String USER_OFFER_STATES = "user_offer_states"; // model 'user_offer_state'
        public static final String V2_COUPONS = "v2_coupons";               // model 'v2_coupons'
        public static final String INFLUENCERS = "influencers";             // model 'influencers'
        public static final String RECHARGE_TRANSACTION_LOGS = "recharge_transaction_logs";
        public static final String INFLUENCER_LEDGERS = "influencer_ledgers"; // model 'influencer_ledger'
        public static final String INFLUENCER_SETTLEMENTS = "influencer_settlements"; // model 'influencer_settlements'
        public static final String USER_COUPON_STATES = "user_coupon_states"; // model 'user_coupon_state'
        public static final String FLAG_LOGS = "flag_logs";                 // model 'flag_logs'
        public static final String PLAYSTORE_REVIEW_UPLOADS = "playstore_review_uploads"; // model 'playstore_review_uploads'
        public static final String CONSULTANT_LIVE_EVENTS = "consultant_live_events"; // model 'consultant_live_events'
        public static final String CONSULTANT_LEAVE_MESSAGE_MAPPINGS =
                "consultant-leave-a-message-mappings"; // model 'consultant-leave-a-message-mapping' -> pluralized (confirmed vs Node model)
        public static final String CONSULTANT_QUICK_NOTES_MESSAGES =
                "consultant_quick_notes_messages"; // model 'consultant_quick_notes_messages'
        public static final String CONSULTANT_AVAILABLES = "consultant_availables";
        public static final String ONLINE_RECORDS = "onlines";
        public static final String COUPON_ACTIVITIES = "coupon_activities";
        public static final String APPLIED_COUPONS = "applied_coupons";    // model 'applied_coupons'
        public static final String CONSULTANT_TAGS = "consultanttags";     // model 'consultanttags'
        public static final String CONSULTANT_PAYOUTS = "consultant-payouts"; // model 'consultant-payout'
        public static final String CONSULTANT_PAYOUT_TRACKS = "consultant-payout-tracks"; // model uses explicit hyphenated name
        public static final String CONSULTANT_PAYOUT_MONTH_ENDS =
                "consultant-payout-monthend-snapshots"; // already plural model name
        public static final String CONSULTANT_FORMS_16 = "consultant_forms_16";
        public static final String BROADCAST_MESSAGES = "braodcast_messages"; // production model typo is contractual
        public static final String REQUESTS = "requests";
        public static final String CHATS = "chats";
        public static final String BANKS = "banks";                        // model 'banks'

        // Java-owned operational records. These do not replace Node business collections.
        public static final String JAVA_CALL_TIMERS = "java_call_timers";
        public static final String JAVA_INTEGRATION_OUTBOX = "java_integration_outbox";
        public static final String JAVA_TASK_REMINDERS = "java_task_reminders";
        public static final String JAVA_BOOKING_CLAIMS = "java_booking_claims";
        public static final String JAVA_LIVE_EVENT_TIMERS = "java_live_event_timers";
        // --- authentication migration collections ---
        public static final String APP_VERSION_MODELS = "app_version_models"; // model 'app_version_model'
        public static final String COMMUNITY_MAPPINGS = "communitymappings";  // model 'communityMappings' -> lowercased (confirmed vs Node model)
        public static final String REFERS = "refers";                        // model 'refer'
        public static final String COMMON_MESSAGES = "common_messages";      // model 'Common_messages'
        public static final String ADMIN_READ_TRACKINGS = "admin_read_trackings"; // model 'adminReadTracking'
        public static final String SHOPIFY_DISCOUNT_COUPONS = "shopify_discount_coupons";
        public static final String SHOPIFY_ORDERS = "shopify_orders";

        private Collections() {}
    }
}
