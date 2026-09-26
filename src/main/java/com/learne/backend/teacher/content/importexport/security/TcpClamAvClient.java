package com.learne.backend.teacher.content.importexport.security;

import com.learne.backend.shared.api.ApiException;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class TcpClamAvClient implements ClamAvClient {
    private static final int CHUNK_SIZE = 8192;
    private static final int SOCKET_TIMEOUT_MILLIS = 10_000;

    private final String host;
    private final int port;

    public TcpClamAvClient(
            @Value("${clamav.host:localhost}") String host,
            @Value("${clamav.port:3310}") int port) {
        this.host = host;
        this.port = port;
    }

    @Override
    public void assertClean(String filename, byte[] bytes) {
        if (bytes == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File không hợp lệ");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), SOCKET_TIMEOUT_MILLIS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("nINSTREAM\n".getBytes(StandardCharsets.US_ASCII));
            for (int offset = 0; offset < bytes.length; offset += CHUNK_SIZE) {
                int length = Math.min(CHUNK_SIZE, bytes.length - offset);
                output.writeInt(length);
                output.write(bytes, offset, length);
            }
            output.writeInt(0);
            output.flush();

            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String response = input.readLine();
            if (response == null || response.isBlank()) {
                throw scanUnavailable();
            }
            String trimmedResponse = response.trim();
            if (trimmedResponse.contains("FOUND")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "File có chứa mã độc hoặc virus");
            }
            if (trimmedResponse.contains("ERROR") || !trimmedResponse.equals("stream: OK")) {
                throw scanUnavailable();
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw scanUnavailable();
        }
    }

    private ApiException scanUnavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "Không thể quét ClamAV lúc này");
    }
}
