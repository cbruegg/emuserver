package com.cbruegg.emuserver.platform.ds;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

public class DsServerOutputReader implements AutoCloseable {

    private static final Pattern portSpecPattern = Pattern.compile("\\[SERVOUT] (screen|audio|input): :([0-9]+)");

    public static record PortSpec(int screenSocketPort, int audioSocketPort, int inputSocketPort) {
    }

    private final Scanner serverOutputScanner;
    private volatile OutputStream redirectTo;
    private volatile PortSpec portSpec;
    private volatile boolean stop = false;
    private final CountDownLatch portSpecSema = new CountDownLatch(1);

    public DsServerOutputReader(InputStream serverOutput) {
        this(serverOutput, System.out);
    }

    public DsServerOutputReader(InputStream serverOutput, OutputStream redirectTo) {
        serverOutputScanner = new Scanner(serverOutput);
        this.redirectTo = redirectTo;
        Thread thread = new Thread(() -> {
            Integer screenPort = null;
            Integer audioPort = null;
            Integer inputPort = null;
            while (!stop && serverOutputScanner.hasNextLine()) {
                var line = serverOutputScanner.nextLine();
                if (portSpec == null) {
                    var matcher = portSpecPattern.matcher(line);
                    if (matcher.find()) {
                        var port = Integer.parseInt(matcher.group(2));
                        switch (matcher.group(1)) {
                            case "screen" -> screenPort = port;
                            case "audio" -> audioPort = port;
                            case "input" -> inputPort = port;
                        }
                        if (screenPort != null && audioPort != null && inputPort != null) {
                            portSpec = new PortSpec(screenPort, audioPort, inputPort);
                            portSpecSema.countDown();
                        }
                    } else {
                        redirect(line);
                    }
                } else {
                    redirect(line);
                }
            }
        });
        thread.setName("DsServerOutputReader");
        thread.start();
    }

    private void redirect(String line) {
        try {
            this.redirectTo.write((line + '\n').getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public PortSpec readPortSpec() {
        while (true) {
            try {
                portSpecSema.await();
                break;
            } catch (InterruptedException ignored) { // TODO Don't ignore, but stop!
            }
        }
        return portSpec;
    }

    public void setRedirectTo(OutputStream redirectTo) {
        this.redirectTo = redirectTo;
    }

    @Override
    public void close() throws Exception {
        serverOutputScanner.close();
        stop = true;
    }
}
