package org.documentknowledge.devlogging.application;

/** Authenticated owned-runtime control; no caller clock or storage path authority. */
public interface LogVolumeObservation {
    void observe(String volumeIdentity, long freeBytes, long totalBytes);
    void observationFailed();
}
