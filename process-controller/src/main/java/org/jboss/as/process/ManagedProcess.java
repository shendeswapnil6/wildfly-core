/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.process;

import static java.lang.Thread.holdsLock;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.as.process.logging.ProcessLogger;
import org.jboss.as.process.protocol.StreamUtils;
import org.jboss.as.process.stdin.Base64OutputStream;
import org.jboss.logging.Logger;

/**
 * A managed process.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 * @author <a href="mailto:kabir.khan@jboss.com">Kabir Khan</a>
 * @author Emanuel Muckenhuber
 */
final class ManagedProcess {

    private final String processName;
    private final List<String> command;
    private final Map<String, String> env;
    private final String workingDirectory;
    private final ProcessLogger log;
    private final Object lock;

    private final ProcessController processController;
    private final String authKey;
    private final boolean isPrivileged;
    private final RespawnPolicy respawnPolicy;

    private OutputStream stdin;
    private volatile State state = State.DOWN;
    private Process process;
    private boolean shutdown;
    private boolean stopRequested = false;
    private final AtomicInteger respawnCount = new AtomicInteger(0);

    public String getAuthKey() {
        return authKey;
    }

    public boolean isPrivileged() {
        return isPrivileged;
    }

    public boolean isRunning() {
        return (state == State.STARTED) || (state == State.STOPPING);
    }

    public boolean isStopping() {
        return state == State.STOPPING;
    }

    enum State {
        DOWN,
        STARTED,
        STOPPING,
        ;
    }

    ManagedProcess(final String processName, final List<String> command, final Map<String, String> env, final String workingDirectory, final Object lock, final ProcessController controller, final String authKey, final boolean privileged, final boolean respawn) {
        if (processName == null) {
            throw ProcessLogger.ROOT_LOGGER.nullVar("processName");
        }
        if (command == null) {
            throw ProcessLogger.ROOT_LOGGER.nullVar("command");
        }
        if (env == null) {
            throw ProcessLogger.ROOT_LOGGER.nullVar("env");
        }
        if (workingDirectory == null) {
            throw ProcessLogger.ROOT_LOGGER.nullVar("workingDirectory");
        }
        if (lock == null) {
            throw ProcessLogger.ROOT_LOGGER.nullVar("lock");
        }
        if (controller == null) {
            throw ProcessLogger.ROOT_LOGGER.nullVar("controller");
        }
        if (authKey == null) {
            throw ProcessLogger.ROOT_LOGGER.nullVar("authKey");
        }
        if (authKey.length() != ProcessController.AUTH_BYTES_ENCODED_LENGTH) {
            throw ProcessLogger.ROOT_LOGGER.invalidLength("authKey");
        }
        this.processName = processName;
        this.command = command;
        this.env = env;
        this.workingDirectory = workingDirectory;
        this.lock = lock;
        processController = controller;
        this.authKey = authKey;
        isPrivileged = privileged;
        respawnPolicy = respawn ? RespawnPolicy.RESPAWN : RespawnPolicy.NONE;
        log = Logger.getMessageLogger(ProcessLogger.class, "org.jboss.as.process." + processName + ".status");
    }

    int incrementAndGetRespawnCount() {
        return respawnCount.incrementAndGet();
    }

    int resetRespawnCount() {
        return respawnCount.getAndSet(0);
    }

    public String getProcessName() {
        return processName;
    }

    public void start() {
        synchronized (lock) {
            if (state != State.DOWN) {
                log.debugf("Attempted to start already-running process '%s'", processName);
                return;
            }
            resetRespawnCount();
            doStart(false);
        }
    }

    public void sendStdin(final InputStream msg) throws IOException {
        assert holdsLock(lock); // Call under lock
        try {
            // WFLY-2697 All writing is in Base64
            Base64OutputStream base64 = getBase64OutputStream(stdin);
            StreamUtils.copyStream(msg, base64);
            base64.close(); // not flush(). close() writes extra data to the stream allowing Base64 input stream
                            // to distinguish end of message
        } catch (IOException e) {
            log.failedToSendDataBytes(e, processName);
            throw e;
        }
    }

    public void reconnect(String scheme, String hostName, int port, boolean managementSubsystemEndpoint, String asAuthKey) {
        assert holdsLock(lock); // Call under lock
        try {
            // WFLY-2697 All writing is in Base64
            Base64OutputStream base64 = getBase64OutputStream(stdin);
            StreamUtils.writeUTFZBytes(base64, scheme);
            StreamUtils.writeUTFZBytes(base64, hostName);
            StreamUtils.writeInt(base64, port);
            StreamUtils.writeBoolean(base64, managementSubsystemEndpoint);
            base64.write(asAuthKey.getBytes());
            base64.close(); // not flush(). close() writes extra data to the stream allowing Base64 input stream
                            // to distinguish end of message
        } catch (IOException e) {
            if(state == State.STARTED) {
                // Only log in case the process is still running
                log.failedToSendReconnect(e, processName);
            }
        }
    }

    void doStart(boolean restart) {
        // Call under lock
        assert holdsLock(lock);
        stopRequested = false;
        final List<String> command = new ArrayList<String>(this.command);
        if(restart) {
            //Add the restart flag to the HC process if we are respawning it
            command.add(CommandLineConstants.PROCESS_RESTARTED);
        }
        log.startingProcess(processName);
        log.debugf("Process name='%s' command='%s' workingDirectory='%s'", processName, command, workingDirectory);
        final ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        builder.directory(new File(workingDirectory));
        final Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            e.printStackTrace();
            processController.operationFailed(processName, ProcessMessageHandler.OperationType.START);
            log.failedToStartProcess(processName);
            return;
        }
        final long startTime = System.currentTimeMillis();
        final OutputStream stdin = process.getOutputStream();
        final InputStream stderr = process.getErrorStream();
        final InputStream stdout = process.getInputStream();
        final Thread stderrThread = new Thread(new ReadTask(stderr, processController.getStderr()));
        stderrThread.setName(String.format("stderr for %s", processName));
        stderrThread.start();
        final Thread stdoutThread = new Thread(new ReadTask(stdout, processController.getStdout()));
        stdoutThread.setName(String.format("stdout for %s", processName));
        stdoutThread.start();
        final Thread joinThread = new Thread(new JoinTask(startTime));
        joinThread.setName(String.format("reaper for %s", processName));
        joinThread.start();
        boolean ok = false;
        try {
            // WFLY-2697 All writing is in Base64
            OutputStream base64 = getBase64OutputStream(stdin);
            base64.write(authKey.getBytes());
            base64.close(); // not flush(). close() writes extra data to the stream allowing Base64 input stream
                            // to distinguish end of message
            ok = true;
        } catch (Exception e) {
            log.failedToSendAuthKey(processName, e);
        }

        this.process = process;
        this.stdin = stdin;

        if(ok) {
            state = State.STARTED;
            processController.processStarted(processName);
        } else {
            processController.operationFailed(processName, ProcessMessageHandler.OperationType.START);
        }
        return;
    }

    public void stop() {
        synchronized (lock) {
            if (state != State.STARTED) {
                log.debugf("Attempted to stop already-stopping or down process '%s'", processName);
                return;
            }
            log.stoppingProcess(processName);
            stopRequested = true;
            StreamUtils.safeClose(stdin);
            state = State.STOPPING;
        }
    }

    public void destroy() {
        synchronized (lock) {
            if(state != State.STOPPING) {
                stop(); // Try to stop before destroying the process
            } else {
                log.debugf("Destroying process '%s'", processName);
                process.destroy();
            }
        }
    }

    public void kill() {
        synchronized (lock) {
            if(state != State.STOPPING) {
                stop(); // Try to stop before killing the process
            } else {
                log.debugf("Attempting to kill -KILL process '%s'", processName);
                if(! ProcessUtils.killProcess(processName)) {
                    // Fallback to destroy if kill is not available
                    log.failedToKillProcess(processName);
                    process.destroy();
                }
            }
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if(shutdown) {
                return;
            }
            shutdown = true;
            if (state == State.STARTED) {
                log.stoppingProcess(processName);
                stopRequested = true;
                StreamUtils.safeClose(stdin);
                state = State.STOPPING;
            } else if (state == State.STOPPING) {
                return;
            } else {
                new Thread() {
                    @Override
                    public void run() {
                        processController.removeProcess(processName);
                    }
                }.start();
            }
        }
    }

    void respawn() {
        synchronized (lock) {
            if (state != State.DOWN) {
                log.debugf("Attempted to respawn already-running process '%s'", processName);
                return;
            }
            doStart(true);
        }
    }

    private static Base64OutputStream getBase64OutputStream(OutputStream toWrap) {
        // We'll call close on Base64OutputStream at the end of each message
        // to serve as a delimiter. Don't let that close the underlying stream.
        OutputStream nonclosing = new FilterOutputStream(toWrap) {
            @Override
            public void close() throws IOException {
                flush();
            }
        };
        return new Base64OutputStream(nonclosing);
    }

    private final class JoinTask implements Runnable {
        private final long startTime;

        public JoinTask(final long startTime) {
            this.startTime = startTime;
        }

        public void run() {
            final Process process;
            synchronized (lock) {
                process = ManagedProcess.this.process;
            }
            int exitCode;
            for (;;) try {
                exitCode = process.waitFor();
                log.processFinished(processName, exitCode);
                break;
            } catch (InterruptedException e) {
                // ignore
            }
            boolean respawn = false;
            boolean slowRespawn = false;
            boolean unlimitedRespawn = false;
            int respawnCount = 0;
            synchronized (lock) {

                final long endTime = System.currentTimeMillis();
                processController.processStopped(processName, endTime - startTime);
                state = State.DOWN;

                if (shutdown) {
                    processController.removeProcess(processName);
                } else if (isPrivileged() && exitCode == ExitCodes.HOST_CONTROLLER_ABORT_EXIT_CODE) {
                    // Host Controller abort. See if there are other running processes the HC
                    // needs to manage. If so we must restart the HC.
                    if (processController.getOngoingProcessCount() > 1) {
                        respawn = true;
                        respawnCount = ManagedProcess.this.incrementAndGetRespawnCount();
                        unlimitedRespawn = true;
                        // We already have servers, so this isn't an abort in the early stages of the
                        // initial HC boot. Likely it's due to a problem in a reload, which will require
                        // some sort of user intervention to resolve. So there is no point in immediately
                        // respawning and spamming the logs.
                        slowRespawn = true;
                    } else {
                        processController.removeProcess(processName);
                        new Thread(new Runnable() {
                            public void run() {
                                processController.shutdown();
                                System.exit(ExitCodes.NORMAL);
                            }
                        }).start();
                    }
                } else if (isPrivileged() && exitCode == ExitCodes.RESTART_PROCESS_FROM_STARTUP_SCRIPT) {
                    // Host Controller restart via exit code picked up by script
                    processController.removeProcess(processName);
                    new Thread(new Runnable() {
                        public void run() {
                            processController.shutdown();
                            System.exit(ExitCodes.RESTART_PROCESS_FROM_STARTUP_SCRIPT);
                        }
                    }).start();

                } else {
                    if(! stopRequested) {
                        respawn = true;
                        respawnCount = ManagedProcess.this.incrementAndGetRespawnCount();
                        if (isPrivileged() && processController.getOngoingProcessCount() > 1) {
                            // This is an HC with live servers to manage, so never give up on
                            // restarting
                            unlimitedRespawn = true;
                        }
                    }
                }
                stopRequested = false;
            }
            if(respawn) {
                respawnPolicy.respawn(respawnCount, ManagedProcess.this, slowRespawn, unlimitedRespawn);
            }
        }
    }

    private final class ReadTask implements Runnable {
        private final InputStream source;
        private final PrintStream target;

        private ReadTask(final InputStream source, final PrintStream target) {
            this.source = source;
            this.target = target;
        }

        public void run() {
            final InputStream source = this.source;
            final String processName = ManagedProcess.this.processName;
            try {
                final BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(source)));
                final OutputStreamWriter writer = new OutputStreamWriter(target);
                String s;
                String prevEscape = "";
                while ((s = reader.readLine()) != null) {
                    // Has ANSI?
                    int i = s.lastIndexOf('\033');
                    int j = i != -1 ? s.indexOf('m', i) : 0;

                    synchronized (target) {
                        writer.write('[');
                        writer.write(processName);
                        writer.write("] ");
                        writer.write(prevEscape);
                        writer.write(s);

                        // Reset if there was ANSI
                        if (j != 0 || prevEscape != "") {
                            writer.write("\033[0m");
                        }
                        writer.write('\n');
                        writer.flush();
                    }


                    // Remember escape code for the next line
                    if (j != 0) {
                        String escape = s.substring(i, j + 1);
                        if (!"\033[0m".equals(escape)) {
                            prevEscape = escape;
                        } else {
                            prevEscape = "";
                        }
                    }
                }
                source.close();
            } catch (IOException e) {
                log.streamProcessingFailed(processName, e);
            } finally {
                StreamUtils.safeClose(source);
            }
        }
    }
}
