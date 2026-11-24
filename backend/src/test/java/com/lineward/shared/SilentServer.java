/*
 * Copyright 2026 Ayyoub Amjahed Abed
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lineward.shared;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A server that accepts every connection and never says a word.
 *
 * <p>The failure a timeout exists for, reproduced exactly: the connection
 * opens, so no connect error comes back, and then nothing arrives. Whatever
 * protocol the client speaks, HTTP or LDAP, it is left waiting for the first
 * byte of an answer. A server that refused the connection would test the
 * other, easier case.
 */
public final class SilentServer implements AutoCloseable {

    private final ServerSocket socket;
    private final List<Socket> accepted = new CopyOnWriteArrayList<>();
    private final Thread acceptor;

    public SilentServer() throws IOException {
        this.socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptor = Thread.ofVirtual().start(this::acceptForever);
    }

    public int port() {
        return socket.getLocalPort();
    }

    /** How many connections came in, so a test can tell "it waited" from "it never got here". */
    public int connections() {
        return accepted.size();
    }

    private void acceptForever() {
        while (!socket.isClosed()) {
            try {
                accepted.add(socket.accept());
            } catch (IOException closed) {
                return;
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
        for (Socket connection : accepted) {
            connection.close();
        }
        acceptor.interrupt();
    }
}
