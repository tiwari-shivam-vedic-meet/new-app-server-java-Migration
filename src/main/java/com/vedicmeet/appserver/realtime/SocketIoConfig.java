package com.vedicmeet.appserver.realtime;

import com.corundumstudio.socketio.Configuration;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIONamespace;
import com.corundumstudio.socketio.SocketIOServer;
import com.vedicmeet.appserver.security.AuthPrincipal;
import com.vedicmeet.appserver.security.AuthUserService;
import com.vedicmeet.appserver.notification.PushNotificationService;
import com.vedicmeet.appserver.support.ChatServerClient;
import org.bson.Document;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Java Socket.IO server that mirrors Node sockets/namespaces/app.js: the {@code /app} namespace,
 * JWT handshake auth (token from {@code handshake.auth.token}) via {@link SocketHandshakeAuthenticator},
 * join the caller's own room ({@code userId}) on connect, and leave on disconnect. Event names come
 * from {@link SocketEvents} so they stay byte-identical with the mobile apps.
 *
 * GATED OFF by default. Mutating handlers are registered only after every runtime dependency is
 * explicitly enabled and configured; the Redis fan-out bridge preserves multi-instance room events.
 */
@Component
@ConditionalOnProperty(prefix = "vedicmeet.socket", name = "enabled", havingValue = "true")
public class SocketIoConfig {

    private static final Logger log = LoggerFactory.getLogger(SocketIoConfig.class);

    private final SocketHandshakeAuthenticator authenticator;
    private final AuthUserService authUserService;
    private final CallSocketDispatcher dispatcher;
    private final SocketEventPublisher events;
    private final PushNotificationService pushNotifications;
    private final ChatServerClient chatServer;
    private final LiveEventService liveEvents;
    private final AppSocketSessionService appSessions;
    private final boolean callExecutionEnabled;
    private final boolean migrationWritesEnabled;
    private final boolean timerWorkerEnabled;
    private final boolean outboxWorkerEnabled;
    private final boolean redisFanoutEnabled;
    private final boolean liveEventExecutionEnabled;
    private final boolean liveEventTimerWorkerEnabled;
    private final boolean appSessionWritesEnabled;
    private final int port;
    private SocketIOServer server;

    public SocketIoConfig(SocketHandshakeAuthenticator authenticator,
                          AuthUserService authUserService,
                          CallSocketDispatcher dispatcher,
                          SocketEventPublisher events,
                          PushNotificationService pushNotifications,
                          ChatServerClient chatServer,
                          LiveEventService liveEvents,
                          AppSocketSessionService appSessions,
                          @Value("${vedicmeet.call.execution-enabled:false}") boolean callExecutionEnabled,
                          @Value("${vedicmeet.migration.writes-enabled:false}") boolean migrationWritesEnabled,
                          @Value("${vedicmeet.call.timer-worker-enabled:false}") boolean timerWorkerEnabled,
                          @Value("${vedicmeet.call.outbox-worker-enabled:false}") boolean outboxWorkerEnabled,
                          @Value("${vedicmeet.socket.redis-fanout-enabled:false}") boolean redisFanoutEnabled,
                          @Value("${vedicmeet.live-event.execution-enabled:false}") boolean liveEventExecutionEnabled,
                          @Value("${vedicmeet.live-event.timer-worker-enabled:false}") boolean liveEventTimerWorkerEnabled,
                          @Value("${vedicmeet.socket.app-session-writes-enabled:false}") boolean appSessionWritesEnabled,
                          @Value("${vedicmeet.socket.port:8082}") int port) {
        this.authenticator = authenticator;
        this.authUserService = authUserService;
        this.dispatcher = dispatcher;
        this.events = events;
        this.pushNotifications = pushNotifications;
        this.chatServer = chatServer;
        this.liveEvents = liveEvents;
        this.appSessions = appSessions;
        this.callExecutionEnabled = callExecutionEnabled;
        this.migrationWritesEnabled = migrationWritesEnabled;
        this.timerWorkerEnabled = timerWorkerEnabled;
        this.outboxWorkerEnabled = outboxWorkerEnabled;
        this.redisFanoutEnabled = redisFanoutEnabled;
        this.liveEventExecutionEnabled = liveEventExecutionEnabled;
        this.liveEventTimerWorkerEnabled = liveEventTimerWorkerEnabled;
        this.appSessionWritesEnabled = appSessionWritesEnabled;
        this.port = port;
    }

    @PostConstruct
    public void start() {
        Configuration config = new Configuration();
        config.setPort(port);

        server = new SocketIOServer(config);
        SocketIONamespace app = server.addNamespace(SocketEvents.NAMESPACE_APP);
        events.attach(app);
        SocketIONamespace liveEvent = server.addNamespace(SocketEvents.NAMESPACE_LIVE_EVENT);
        events.attach("live_event", liveEvent);

        app.addConnectListener(this::authenticateClient);
        liveEvent.addConnectListener(this::authenticateClient);

        app.addDisconnectListener(client -> {
            Object room = client.get("userId");
            if (room != null) client.leaveRoom(String.valueOf(room));
            if (appSessionWritesEnabled && room != null) {
                Object start = client.get("activeStartEpoch");
                double seconds = start instanceof Number n
                        ? Math.max(0, (System.currentTimeMillis() - n.longValue()) / 1_000.0) : 0;
                appSessions.presence(clientRole(client), String.valueOf(room), "offline", seconds);
            }
        });

        // Event catalog (SocketEvents). Read-only call events are safe to shadow and contract-test.
        app.addEventListener(SocketEvents.TEST_EVENT, Object.class, (client, data, ack) ->
                CompletableFuture.delayedExecutor(12, TimeUnit.SECONDS).execute(() ->
                        client.sendEvent(SocketEvents.TEST_EVENT,
                                Map.of("message", "Test event received"))));

        app.addEventListener(SocketEvents.VERIFY_END_CALL, Map.class, (client, data, ack) ->
                withClient(client, () -> ack.sendAckData(dispatcher.verifyEndCall(text(data, "roomId")))));
        app.addEventListener(SocketEvents.CHECK_CALL_STATUS, Map.class, (client, data, ack) ->
                withClient(client, () -> ack.sendAckData(dispatcher.checkCallStatus(text(data, "roomId")))));
        app.addEventListener(SocketEvents.ROOM_REMAINING_TIME, Map.class, (client, data, ack) ->
                withClient(client, () -> ack.sendAckData(dispatcher.roomRemainingTime(
                        text(data, "roomId"), text(data, "callStatus")))));
        app.addEventListener(SocketEvents.JOIN_ROOM, Map.class, (client, data, ack) -> {
            String roomId = text(data, "roomId");
            if (roomId != null && !roomId.isBlank()) client.joinRoom(roomId);
        });
        liveEvent.addDisconnectListener(client -> {
            Object room = client.get("userId");
            if (room != null) client.leaveRoom(String.valueOf(room));
        });
        app.addEventListener(SocketEvents.LEAVE_ROOM, Map.class, (client, data, ack) -> {
            String roomId = text(data, "roomId");
            if (roomId != null && !roomId.isBlank()) client.leaveRoom(roomId);
        });

        if (appSessionWritesEnabled && (!migrationWritesEnabled || !outboxWorkerEnabled
                || !redisFanoutEnabled)) {
            throw new IllegalStateException("APP_SOCKET_WRITES_ENABLED requires migration writes, "
                    + "the integration outbox worker and Redis socket fan-out");
        }
        registerAppSessionHandlers(app);

        if (callExecutionEnabled && (!migrationWritesEnabled || !timerWorkerEnabled
                || !outboxWorkerEnabled || !redisFanoutEnabled
                || !pushNotifications.callTransportReady() || !chatServer.isReady())) {
            throw new IllegalStateException(
                    "CALL_EXECUTION_ENABLED requires writes, timer and outbox workers, Redis fan-out, "
                            + "FCM/APNs transports and CHAT_SERVER_URL");
        }

        if (callExecutionEnabled) {
            app.addEventListener(SocketEvents.ACCEPT_CALL, Map.class, (client, data, ack) ->
                    withClient(client, () -> dispatcher.acceptCall(clientRole(client), clientId(client),
                            text(data, "roomId"), text(data, "type"))));
            app.addEventListener(SocketEvents.CANCEL_CALL, Map.class, (client, data, ack) ->
                    withClient(client, () -> dispatcher.cancelCall(clientRole(client), clientId(client),
                            text(data, "roomId"), bool(data, "isCallSessionType"))));
            app.addEventListener(SocketEvents.END_CALL, Map.class, (client, data, ack) ->
                    withClient(client, () -> ack.sendAckData(dispatcher.endCall(clientRole(client),
                            clientId(client), text(data, "waitlistId"), decimal(data, "elapsedTime")))));
            app.addEventListener(SocketEvents.EXTEND_CALL, Map.class, (client, data, ack) ->
                    withClient(client, () -> dispatcher.extendCall(text(data, "roomId"),
                            whole(data, "additionalSeconds"))));
        }

        if (liveEventExecutionEnabled && (!migrationWritesEnabled || !liveEventTimerWorkerEnabled
                || !timerWorkerEnabled || !outboxWorkerEnabled || !redisFanoutEnabled
                || !chatServer.isReady())) {
            throw new IllegalStateException(
                    "LIVE_EVENT_EXECUTION_ENABLED requires writes, live-event and call timer workers, "
                            + "outbox worker, Redis fan-out and CHAT_SERVER_URL");
        }
        registerLiveEventHandlers(liveEvent);

        server.start();
        log.info("Socket.IO /app and /live_event namespaces started on port {} callMode={} liveEventMode={}",
                port, callExecutionEnabled ? "java-call-owner" : "read-only-shadow",
                liveEventExecutionEnabled ? "java-owner" : "read-only-shadow");
    }

    @PreDestroy
    public void stop() {
        events.detach();
        if (server != null) server.stop();
    }

    /** Node reads socket.handshake.auth.token; fall back to the ?token= URL param. */
    private String handshakeToken(SocketIOClient client) {
        Object auth = client.getHandshakeData().getAuthToken();
        if (auth instanceof java.util.Map) {
            Object t = ((java.util.Map<?, ?>) auth).get("token");
            if (t != null) return String.valueOf(t);
        }
        return client.getHandshakeData().getSingleUrlParam("token");
    }

    private void authenticateClient(SocketIOClient client) {
        String token = handshakeToken(client);
        try {
            AuthPrincipal principal = authenticator.authenticate(token);
            // Production JWTs carry identity claims, not the Mongo _id. Resolve and validate the
            // current account before joining its personal room in either namespace.
            Document user = authUserService.loadForSocket(principal);
            if (user == null || user.get("_id") == null
                    || !authUserService.tokenVersionValid(principal, user)) {
                throw new SocketHandshakeAuthenticator.SocketAuthException("Socket account not found");
            }
            String room = String.valueOf(user.get("_id"));
            client.set("userId", room);
            client.set("role", principal.getRole());
            client.set("authDocument", user);
            client.set("activeStartEpoch", System.currentTimeMillis());
            client.joinRoom(room);
            if (appSessionWritesEnabled && SocketEvents.NAMESPACE_APP.equals(client.getNamespace().getName())) {
                appSessions.presence(principal.getRole(), room, "online", 0);
            }
        } catch (RuntimeException authFailed) {
            log.debug("socket auth rejected: {}", authFailed.getMessage());
            client.disconnect();
        }
    }

    @SuppressWarnings("unchecked")
    private void registerAppSessionHandlers(SocketIONamespace namespace) {
        namespace.addEventListener(SocketEvents.SESSION_SWITCH_PERMISSION, Map.class,
                (client, data, ack) -> appFire(client, () -> {
                    AppSocketSessionService.Dispatch dispatch = appSessions.sessionSwitchPermission(
                            clientRole(client), clientId(client), text(data, "waitlistId"),
                            text(data, "channel"), text(data, "type"));
                    dispatch.targets().forEach(target -> events.emitToRoom(target,
                            SocketEvents.LISTENING_SESSION_SWITCH_PERMISSION, dispatch.payload()));
                }));

        namespace.addEventListener(SocketEvents.SESSION_SWITCH_LOGS, Map.class,
                (client, data, ack) -> appWriteFire(client, () -> appSessions.sessionSwitchLog(
                        clientRole(client), clientId(client), text(data, "waitlistId"),
                        text(data, "channel"))));

        namespace.addEventListener(SocketEvents.GET_CONSULTANT_DETAILS, Map.class,
                (client, data, ack) -> appAck(ack, () -> successData(
                        appSessions.consultantDetails(clientRole(client), clientId(client),
                                text(data, "waitlistId"))), "Failed to get consultant details"));

        namespace.addEventListener(SocketEvents.GET_SESSION_DETAILS, Map.class,
                (client, data, ack) -> appAck(ack, () -> successData(
                        appSessions.sessionDetails(clientRole(client), clientId(client),
                                text(data, "waitlistId"))), "Failed to get session details"));

        namespace.addEventListener(SocketEvents.GET_BLOCKED_CHATS_RANGES, Map.class,
                (client, data, ack) -> appAck(ack, () -> successData(
                        appSessions.blockedChatRanges(clientRole(client), clientId(client),
                                text(data, "waitlistId"))), "No completed waitlists found"));

        namespace.addEventListener(SocketEvents.USER_WAITLIST_STATUS, Object.class,
                (client, data, ack) -> appAck(ack, () -> appSessions.waitlistStatus(
                        clientRole(client), clientId(client)), "Failed to get waitlist status"));

        namespace.addEventListener(SocketEvents.ON_GOING_SESSION_ACTIVITY, Map.class,
                (client, data, ack) -> appFire(client, () -> {
                    AppSocketSessionService.Dispatch dispatch = appSessions.ongoingActivity(
                            clientRole(client), clientId(client), text(data, "waitlistId"),
                            text(data, "to"), text(data, "type"), text(data, "message"));
                    dispatch.targets().forEach(target -> events.emitToRoom(target,
                            SocketEvents.ON_GOING_SESSION_ACTIVITY, dispatch.payload()));
                }));

        namespace.addEventListener(SocketEvents.GET_LAST_CALL_MODE, Map.class,
                (client, data, ack) -> appFire(client, () -> {
                    AppSocketSessionService.Dispatch dispatch = appSessions.lastCallMode(
                            clientRole(client), clientId(client), text(data, "waitlistId"),
                            text(data, "roomId"), text(data, "currentChannel"));
                    dispatch.targets().forEach(target -> events.emitToRoom(target,
                            SocketEvents.GET_LAST_CALL_MODE, dispatch.payload()));
                }));

        namespace.addEventListener(SocketEvents.USER_CANCEL_SESSION, Map.class,
                (client, data, ack) -> appWriteAck(ack, () -> appSessions.cancelSession(
                        clientRole(client), clientId(client), text(data, "waitlistId"),
                        text(data, "reason"), text(data, "used_for"))));

        namespace.addEventListener(SocketEvents.USER_JOIN_WAITLIST, Map.class,
                (client, data, ack) -> appWriteAck(ack, () -> appSessions.rejoinWaitlist(
                        clientRole(client), clientId(client), text(data, "consultantId"),
                        text(data, "roomId"))));

        namespace.addEventListener(SocketEvents.CAN_SEND_MESSAGE, Map.class,
                (client, data, ack) -> appWriteAck(ack, () -> appSessions.canSendMessage(
                        clientRole(client), clientId(client), text(data, "waitlistId"),
                        text(data, "message"))));

        namespace.addEventListener(SocketEvents.CAN_SEND_MESSAGE_IN_LEAVE_FOR_CONSULTANT, Map.class,
                (client, data, ack) -> appWriteAck(ack, () -> appSessions.canSendLeaveMessage(
                        clientRole(client), clientId(client), text(data, "consultantId"),
                        text(data, "userId"))));
    }

    private void appWriteFire(SocketIOClient client, Runnable operation) {
        if (!appSessionWritesEnabled) return;
        appFire(client, operation);
    }

    private void appFire(SocketIOClient client, Runnable operation) {
        try { operation.run(); }
        catch (RuntimeException error) {
            log.warn("/app socket event rejected actor={} reason={}", clientId(client), message(error));
        }
    }

    private void appWriteAck(com.corundumstudio.socketio.AckRequest ack, AppOperation operation) {
        if (!appSessionWritesEnabled) {
            ack.sendAckData(failure("Java /app session writes are disabled"));
            return;
        }
        appAck(ack, operation, "Operation failed");
    }

    private void appAck(com.corundumstudio.socketio.AckRequest ack, AppOperation operation,
                        String fallback) {
        try { ack.sendAckData(operation.run()); }
        catch (RuntimeException error) {
            ack.sendAckData(failure(message(error).isBlank() ? fallback : message(error)));
        }
    }

    @SuppressWarnings("unchecked")
    private void registerLiveEventHandlers(SocketIONamespace namespace) {
        namespace.addEventListener(SocketEvents.GET_LIVE_EVENT, Object.class,
                (client, data, ack) -> liveAck(ack, () -> liveEvents.events()));

        namespace.addEventListener(SocketEvents.JOIN_LIVE_EVENT, Map.class, (client, data, ack) ->
                liveAck(ack, () -> {
                    String eventId = text(data, "event_id");
                    List<Document> messages = liveEvents.joinEvent(eventId, clientId(client),
                            clientRole(client), liveEventExecutionEnabled);
                    client.joinRoom(eventId);
                    return Map.of("messages", messages);
                }));

        namespace.addEventListener(SocketEvents.GET_ROOM_USERS, Map.class, (client, data, ack) ->
                liveAck(ack, () -> {
                    String eventId = text(data, "event_id");
                    List<String> blocked = liveEvents.blockedUsers(eventId);
                    List<Map<String, Object>> usersInRoom = namespace.getRoomOperations(eventId)
                            .getClients().stream().map(roomClient -> roomUser(roomClient, blocked)).toList();
                    return Map.of("users", usersInRoom);
                }));

        namespace.addEventListener(SocketEvents.GET_WAITLIST_USERS, Map.class, (client, data, ack) ->
                liveAck(ack, () -> success("Waitlist users fetched successfully",
                        liveEvents.waitlist(clientId(client), clientRole(client), text(data, "eventId")))));

        namespace.addEventListener(SocketEvents.EVENT_CONSULTANT_ACTION, Map.class, (client, data, ack) ->
                liveWriteAck(ack, () -> {
                    if (!"block_status".equals(text(data, "actionType"))) {
                        throw new IllegalStateException("Unsupported consultant action");
                    }
                    String userId = text(data, "user_id");
                    boolean blocked = bool(data, "actionValue");
                    Document status = liveEvents.toggleBlock(text(data, "event_id"), userId,
                            clientId(client), clientRole(client), blocked);
                    events.emitToRoom("live_event", userId, SocketEvents.EVENT_CONSULTANT_ACTION,
                            Map.of("actionType", "block_status", "actionValue", blocked));
                    return Map.of("blockStatus", status);
                }));

        namespace.addEventListener(SocketEvents.SEND_MESSAGE, Map.class, (client, data, ack) ->
                liveWriteAck(ack, () -> {
                    Document actor = client.get("authDocument");
                    String actorName = actor == null ? "" : string(actor.get("name"),
                            string(actor.get("accountName"), ""));
                    Document message = liveEvents.addMessage(text(data, "eventId"),
                            text(data, "message"), actorName, clientId(client));
                    namespace.getRoomOperations(text(data, "eventId"))
                            .sendEvent(SocketEvents.RECEIVE_MESSAGE, message);
                    return "message sent";
                }));

        namespace.addEventListener(SocketEvents.JOIN_WAITLIST_LIVE_EVENT, Map.class, (client, data, ack) ->
                liveWriteAck(ack, () -> {
                    LiveEventService.JoinResult joined = liveEvents.joinWaitlist(clientId(client),
                            clientRole(client), text(data, "eventId"), data.get("formData"));
                    Map<String, Object> payload = joinedPayload(joined, false);
                    namespace.getRoomOperations(text(joined.waitlist(), "consultant_id"))
                            .sendEvent(SocketEvents.USER_JOINED_WAITLIST, client, payload);
                    return success("Waitlist entry created successfully",
                            Map.of("action", joined.action(), "waitlist", joined.waitlist(),
                                    "roomId", joined.roomId()));
                }));

        namespace.addEventListener(SocketEvents.CALL_NEXT_USER, Map.class, (client, data, ack) ->
                liveWriteAck(ack, () -> {
                    LiveEventService.JoinResult joined = liveEvents.callNext(clientId(client),
                            clientRole(client), text(data, "eventId"));
                    namespace.getRoomOperations(text(joined.waitlist(), "user_id"))
                            .sendEvent(SocketEvents.CONSULTANT_JOINED_CHAT, client,
                                    joinedPayload(joined, true));
                    return success("Waitlist entry created successfully",
                            Map.of("action", joined.action(), "waitlist", joined.waitlist(),
                                    "roomId", joined.roomId()));
                }));

        namespace.addEventListener(SocketEvents.LEAVE_LIVE_EVENT, Map.class, (client, data, ack) -> {
            String eventId = text(data, "event_id");
            try { liveEvents.leaveEvent(eventId, clientId(client), clientRole(client),
                    liveEventExecutionEnabled); }
            finally { if (eventId != null) client.leaveRoom(eventId); }
        });
    }

    private void liveWriteAck(com.corundumstudio.socketio.AckRequest ack, LiveOperation operation) {
        if (!liveEventExecutionEnabled) {
            ack.sendAckData(failure("Java live-event execution is disabled"));
            return;
        }
        liveAck(ack, operation);
    }

    private void liveAck(com.corundumstudio.socketio.AckRequest ack, LiveOperation operation) {
        try { ack.sendAckData(operation.run()); }
        catch (RuntimeException error) { ack.sendAckData(failure(message(error))); }
    }

    private Map<String, Object> roomUser(SocketIOClient client, List<String> blocked) {
        Document actor = client.get("authDocument");
        String id = clientId(client);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userId", id);
        user.put("name", actor == null ? null : actor.get("name"));
        user.put("isBlocked", blocked.contains(id));
        user.put("wallet", actor == null ? null : money(actor.get("wallet")));
        user.put("profileImage", actor == null ? null : actor.get("profileImage"));
        return user;
    }

    private Map<String, Object> joinedPayload(LiveEventService.JoinResult joined, boolean consultant) {
        Document user = joined.user() == null ? new Document() : joined.user();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", joined.action());
        payload.put("user", Map.of("_id", text(user, "_id"),
                "name", string(user.get("name"), string(doc(user.get("request_form")).get("firstName"), "")),
                "profileImage", user.get("profileImage") == null ? "" : user.get("profileImage")));
        if (consultant || "connect_now".equals(joined.action())) payload.put("waitlist", joined.waitlist());
        else payload.put("waitlistEntry", joined.waitlist());
        payload.put("roomId", joined.roomId());
        return payload;
    }

    private Map<String, Object> success(String message, Object data) {
        return new LinkedHashMap<>(Map.of("success", true, "message", message, "data", data));
    }

    private Map<String, Object> successData(Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", data);
        return result;
    }

    private Map<String, Object> failure(String message) {
        return new LinkedHashMap<>(Map.of("success", false, "message", message));
    }

    private String message(Exception error) {
        return error.getMessage() == null ? "Internal server error" : error.getMessage();
    }

    private Document doc(Object value) { return value instanceof Document d ? d : new Document(); }
    private String string(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
    private String money(Object value) {
        if (value instanceof Number n) return String.format(java.util.Locale.US, "%.2f", n.doubleValue());
        return value == null ? null : String.valueOf(value);
    }

    @FunctionalInterface private interface LiveOperation { Object run(); }
    @FunctionalInterface private interface AppOperation { Object run(); }

    private void withClient(SocketIOClient client, Runnable action) {
        events.bind(client);
        try {
            action.run();
        } finally {
            events.unbind();
        }
    }

    private String text(Map<?, ?> data, String key) {
        if (data == null || data.get(key) == null) return null;
        return String.valueOf(data.get(key));
    }

    private String clientId(SocketIOClient client) {
        Object value = client.get("userId");
        return value == null ? null : String.valueOf(value);
    }

    private String clientRole(SocketIOClient client) {
        Object value = client.get("role");
        return value == null ? null : String.valueOf(value);
    }

    private boolean bool(Map<?, ?> data, String key) {
        Object value = data == null ? null : data.get(key);
        return value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
    }

    private long whole(Map<?, ?> data, String key) {
        Object value = data == null ? null : data.get(key);
        if (value instanceof Number n) return n.longValue();
        try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private double decimal(Map<?, ?> data, String key) {
        Object value = data == null ? null : data.get(key);
        if (value instanceof Number n) return n.doubleValue();
        try { return value == null ? 0 : Double.parseDouble(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
