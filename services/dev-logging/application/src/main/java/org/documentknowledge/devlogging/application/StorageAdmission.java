package org.documentknowledge.devlogging.application;

/** Trusted owned-volume observation; failures must reject writes without recursive logging. */
@FunctionalInterface
public interface StorageAdmission {
    void requireWritable();
}
