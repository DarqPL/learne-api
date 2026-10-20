package com.learne.backend.teacher.content.audio.service;

/** Managed lesson audio only; successful return includes an already absent asset. */
public interface MediaDeletePort { void delete(String publicId); }
