package com.vedicmeet.appserver.media;

import org.bson.Document;

import java.util.List;
import java.util.Map;

/** Network boundary for LiveKit participant tokens and Egress recording. */
public interface LiveKitProvider {
    Map<String, Object> participantToken(String roomName, String identity, boolean canPublish);
    Document startAudioRecording(String roomName);
    Document stopRecording(String egressId);
    List<Document> activeRecordings(String roomName);
    boolean isReady();
}
