package com.learne.backend.teacher.content.importexport.security;

public interface ClamAvClient {
    void assertClean(String filename, byte[] bytes);
}
