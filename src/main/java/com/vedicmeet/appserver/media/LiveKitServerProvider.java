package com.vedicmeet.appserver.media;

import io.livekit.server.AccessToken;
import io.livekit.server.CanPublish;
import io.livekit.server.CanSubscribe;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import livekit.LivekitEgress;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import retrofit2.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Official LiveKit SDK adapter matching Node {@code utils/classes/livekit.js}.
 * No network client is usable unless the independent provider gate and all credentials are set.
 */
@Component
public class LiveKitServerProvider implements LiveKitProvider {

    private final boolean enabled;
    private final String serverUrl;
    private final String apiKey;
    private final String apiSecret;
    private final long ttlMillis;
    private final String s3AccessKey;
    private final String s3Secret;
    private final String s3Region;
    private final String s3Bucket;

    public LiveKitServerProvider(
            @Value("${vedicmeet.livekit.enabled:false}") boolean enabled,
            @Value("${vedicmeet.livekit.server-url:}") String serverUrl,
            @Value("${vedicmeet.livekit.api-key:}") String apiKey,
            @Value("${vedicmeet.livekit.api-secret:}") String apiSecret,
            @Value("${vedicmeet.livekit.token-ttl-hours:10}") long ttlHours,
            @Value("${vedicmeet.aws.access-key:}") String s3AccessKey,
            @Value("${vedicmeet.aws.secret-key:}") String s3Secret,
            @Value("${vedicmeet.aws.region:}") String s3Region,
            @Value("${vedicmeet.aws.bucket:}") String s3Bucket) {
        this.enabled = enabled;
        this.serverUrl = value(serverUrl);
        this.apiKey = value(apiKey);
        this.apiSecret = value(apiSecret);
        this.ttlMillis = Duration.ofHours(Math.max(1, ttlHours)).toMillis();
        this.s3AccessKey = value(s3AccessKey);
        this.s3Secret = value(s3Secret);
        this.s3Region = value(s3Region);
        this.s3Bucket = value(s3Bucket);
    }

    @Override
    public Map<String, Object> participantToken(String roomName, String identity, boolean canPublish) {
        requireReady();
        required(roomName, "ROOM_NAME_REQUIRED");
        required(identity, "PARTICIPANT_IDENTITY_REQUIRED");
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(identity);
        token.setTtl(ttlMillis);
        token.addGrants(new RoomJoin(true), new RoomName(roomName),
                new CanPublish(canPublish), new CanSubscribe(true));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token.toJwt());
        result.put("roomName", roomName);
        result.put("identity", identity);
        result.put("serverUrl", serverUrl);
        return result;
    }

    @Override
    public Document startAudioRecording(String roomName) {
        requireReady();
        requireS3();
        required(roomName, "ROOM_NAME_REQUIRED");
        LivekitEgress.S3Upload destination = LivekitEgress.S3Upload.newBuilder()
                .setAccessKey(s3AccessKey).setSecret(s3Secret).setRegion(s3Region)
                .setBucket(s3Bucket).build();
        String path = "recordings/" + safeRoom(roomName) + "-" + System.currentTimeMillis() + ".ogg";
        LivekitEgress.EncodedFileOutput output = LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(LivekitEgress.EncodedFileType.OGG).setFilepath(path)
                .setS3(destination).build();
        try {
            Response<LivekitEgress.EgressInfo> response = egress().startRoomCompositeEgress(
                    roomName, output, "", null, null, true, false, "").execute();
            return body(response, "LIVEKIT_START_RECORDING_FAILED");
        } catch (IOException failure) {
            throw new IllegalStateException("LIVEKIT_START_RECORDING_FAILED", failure);
        }
    }

    @Override
    public Document stopRecording(String egressId) {
        requireReady();
        required(egressId, "EGRESS_ID_REQUIRED");
        try {
            return body(egress().stopEgress(egressId).execute(), "LIVEKIT_STOP_RECORDING_FAILED");
        } catch (IOException failure) {
            throw new IllegalStateException("LIVEKIT_STOP_RECORDING_FAILED", failure);
        }
    }

    @Override
    public List<Document> activeRecordings(String roomName) {
        requireReady();
        try {
            Response<List<LivekitEgress.EgressInfo>> response = egress()
                    .listEgress(value(roomName), "", true).execute();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IllegalStateException("LIVEKIT_LIST_RECORDINGS_FAILED_HTTP_" + response.code());
            }
            return response.body().stream().map(this::document).toList();
        } catch (IOException failure) {
            throw new IllegalStateException("LIVEKIT_LIST_RECORDINGS_FAILED", failure);
        }
    }

    @Override
    public boolean isReady() {
        return enabled && !serverUrl.isBlank() && !apiKey.isBlank() && !apiSecret.isBlank();
    }

    private EgressServiceClient egress() {
        String httpUrl = serverUrl.replaceFirst("^wss://", "https://")
                .replaceFirst("^ws://", "http://");
        return EgressServiceClient.create(httpUrl, apiKey, apiSecret);
    }

    private Document body(Response<LivekitEgress.EgressInfo> response, String error) {
        if (!response.isSuccessful() || response.body() == null) {
            throw new IllegalStateException(error + "_HTTP_" + response.code());
        }
        return document(response.body());
    }

    private Document document(LivekitEgress.EgressInfo info) {
        List<Document> files = new ArrayList<>();
        for (LivekitEgress.FileInfo file : info.getFileResultsList()) {
            files.add(new Document("filename", file.getFilename()).append("location", file.getLocation())
                    .append("duration", file.getDuration()).append("size", file.getSize()));
        }
        return new Document("egressId", info.getEgressId()).append("roomId", info.getRoomId())
                .append("roomName", info.getRoomName()).append("status", info.getStatus().name())
                .append("fileResults", files).append("error", info.getError());
    }

    private void requireReady() {
        if (!isReady()) throw new IllegalStateException("LIVEKIT_NOT_CONFIGURED");
    }

    private void requireS3() {
        if (s3AccessKey.isBlank() || s3Secret.isBlank() || s3Region.isBlank() || s3Bucket.isBlank()) {
            throw new IllegalStateException("LIVEKIT_EGRESS_S3_NOT_CONFIGURED");
        }
    }

    private void required(String input, String error) {
        if (input == null || input.isBlank()) throw new IllegalArgumentException(error);
    }

    private String safeRoom(String input) { return input.replaceAll("[^A-Za-z0-9._-]", "_"); }
    private static String value(String input) { return input == null ? "" : input.trim(); }
}
