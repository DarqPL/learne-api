package com.learne.backend.teacher.content.audio.service;

import com.learne.backend.storage.dto.StorageResourceType;

/** Managed lesson audio and course-cover media; successful return includes an already absent asset. */
public interface MediaDeletePort {
    void delete(String publicId);

    default void delete(String publicId, StorageResourceType resourceType) {
        if (resourceType == StorageResourceType.IMAGE) {
            throw new UnsupportedOperationException("Image deletion is not implemented");
        }
        delete(publicId);
    }
}
