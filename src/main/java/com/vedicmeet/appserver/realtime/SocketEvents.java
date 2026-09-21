package com.vedicmeet.appserver.realtime;

/**
 * The Socket.IO event catalog for the {@code /app} namespace, transcribed byte-for-byte from the Node
 * source (sockets/namespaces/app.js + the emits in utils/classes/call.js). COPILOT_HANDOFF.md Prompt F
 * requires the event names/payloads stay identical, so these constants are the single source of truth
 * the Java {@code SocketIoConfig} registers handlers/emitters against.
 *
 * Do not rename these — they are the wire contract with the mobile apps.
 */
public final class SocketEvents {

    private SocketEvents() {}

    /** Handshake: token is read from {@code socket.handshake.auth.token} (see SocketHandshakeAuthenticator). */
    public static final String NAMESPACE_APP = "/app";
    public static final String NAMESPACE_LIVE_EVENT = "/live_event";

    // ---- incoming (client → server), from app.js socket.on(...) ----
    public static final String ACCEPT_CALL = "accept_call";
    public static final String CANCEL_CALL = "cancel_call";
    public static final String END_CALL = "end_call";
    public static final String VERIFY_END_CALL = "verify_end_call";
    public static final String EXTEND_CALL = "extend_call";
    public static final String CHECK_CALL_STATUS = "check_call_status";
    public static final String ROOM_REMAINING_TIME = "room_remaining_time";
    public static final String CAN_SEND_MESSAGE = "can_send_message";
    public static final String CAN_SEND_MESSAGE_IN_LEAVE_FOR_CONSULTANT = "can_send_message_in_leave_for_consultant";
    public static final String GET_CONSULTANT_DETAILS = "get_consultant_details";
    public static final String GET_LAST_CALL_MODE = "get_last_call_mode";
    public static final String GET_BLOCKED_CHATS_RANGES = "get-blocked-chats-ranges";
    public static final String GET_SESSION_DETAILS = "get-session-details";
    public static final String JOIN_ROOM = "join_room";
    public static final String LEAVE_ROOM = "leave_room";
    public static final String ON_GOING_SESSION_ACTIVITY = "on_going_session_activity";
    public static final String SESSION_SWITCH_LOGS = "session_switch_logs";
    public static final String SESSION_SWITCH_PERMISSION = "session_switch_permission";
    public static final String USER_CANCEL_SESSION = "user_cancel_session";
    public static final String USER_JOIN_WAITLIST = "user_join_waitlist";
    public static final String USER_WAITLIST_STATUS = "user_waitlist_status";
    public static final String TEST_EVENT = "test_event";

    // ---- outgoing (server → client), from app.js + call.js emits ----
    public static final String CALL_ERROR = "call_error";
    public static final String CALL_STATUS_UPDATE = "call_status_update";
    public static final String CALL_CANCELED = "call_canceled";
    public static final String CALL_PARTIALLY_ACCEPTED = "call_partially_accepted";
    public static final String CALL_MISSED_CALLING_NEXT_CONSULTANT = "call_missed_calling_next_consultant";
    public static final String EXTEND_CALL_FAILED = "extend_call_failed";
    public static final String LISTENING_SESSION_SWITCH_PERMISSION = "listening_session_switch_permission";
    public static final String APP_ACTION = "app_action";

    // ---- /live_event incoming (client -> server) ----
    public static final String GET_LIVE_EVENT = "get_live_event";
    public static final String JOIN_LIVE_EVENT = "join_live_event";
    public static final String EVENT_CONSULTANT_ACTION = "event_consultant_action";
    public static final String GET_ROOM_USERS = "get_room_users";
    public static final String SEND_MESSAGE = "send_message";
    public static final String JOIN_WAITLIST_LIVE_EVENT = "join_waitlist_live_event";
    public static final String CALL_NEXT_USER = "call_next_user";
    public static final String GET_WAITLIST_USERS = "get_waitlist_users";
    public static final String LEAVE_LIVE_EVENT = "leave_live_event";

    // ---- /live_event outgoing ----
    public static final String RECEIVE_MESSAGE = "receive_message";
    public static final String USER_JOINED_WAITLIST = "user_joined_waitlist";
    public static final String CONSULTANT_JOINED_CHAT = "consultant_joined_chat";
    public static final String SESSION_ENDED_LEAVE_CHAT = "session_ended_leave_chat";
}
