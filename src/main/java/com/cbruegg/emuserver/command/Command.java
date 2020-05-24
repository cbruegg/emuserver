package com.cbruegg.emuserver.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.Lock;

public interface Command {
    String getId();

    default boolean requiresConfirmation() {
        return false;
    }

    default String getArguments() {
        return "";
    }

    default String serialize() {
        return getId() + " " + getArguments() + "\n";
    }

    default void writeTo(OutputStream outputStream, Lock lock, InputStream gameInputConfirmationStream) throws IOException {
        var bytes = serialize().getBytes(StandardCharsets.UTF_8);
        var lengthBytes = new byte[]{(byte) (bytes.length >> 24), (byte) (bytes.length >> 16), (byte) (bytes.length >> 8), (byte) (bytes.length)};
        lock.lock();
        try {
            outputStream.write(lengthBytes);
            outputStream.write(bytes);

            if (requiresConfirmation()) {
                gameInputConfirmationStream.readNBytes(256);
            }
        } finally {
            lock.unlock();
        }
    }

    record Stop() implements Command {
        @Override
        public String getId() {
            return "Stop";
        }
    }

    record Pause() implements Command {
        @Override
        public String getId() {
            return "Pause";
        }
    }

    record Resume() implements Command {
        @Override
        public String getId() {
            return "Resume";
        }
    }

    record ActivateInput(int input, double value) implements Command {
        @Override
        public String getId() {
            return "ActivateInput";
        }

        @Override
        public String getArguments() {
            return input + " " + value;
        }
    }

    record DeactivateInput(int input) implements Command {
        @Override
        public String getId() {
            return "DeactivateInput";
        }

        @Override
        public String getArguments() {
            return String.valueOf(input);
        }
    }

    record ResetInput() implements Command {
        @Override
        public String getId() {
            return "ResetInput";
        }
    }

    record SaveGameSave() implements Command {
        @Override
        public String getId() {
            return "SaveGameSave";
        }
    }

    record LoadGameSave() implements Command {
        @Override
        public String getId() {
            return "LoadGameSave";
        }
    }

    record SaveState(String fileName) implements Command {
        @Override
        public String getId() {
            return "SaveState";
        }

        @Override
        public String getArguments() {
            return fileName;
        }

        @Override
        public boolean requiresConfirmation() {
            return true;
        }
    }

    record LoadState(String fileName) implements Command {
        @Override
        public String getId() {
            return "LoadState";
        }

        @Override
        public String getArguments() {
            return fileName;
        }

        @Override
        public boolean requiresConfirmation() {
            return true;
        }
    }

    record AddCheat() implements Command {
        @Override
        public String getId() {
            return "AddCheat";
        }
    }

    record ResetCheats() implements Command {
        @Override
        public String getId() {
            return "ResetCheats";
        }
    }

    record SetSpeed(double speed) implements Command {
        @Override
        public String getId() {
            return "SetSpeed";
        }

        @Override
        public String getArguments() {
            return String.valueOf(speed);
        }
    }
}
