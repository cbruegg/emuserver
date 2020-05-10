package com.cbruegg.emuserver.command;

public interface Command {
    String getId();

    default String getArguments() {
        return "";
    }

    default String serialize() {
        return getId() + " " + getArguments();
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

    record SaveState() implements Command {
        @Override
        public String getId() {
            return "SaveState";
        }
    }

    record LoadState() implements Command {
        @Override
        public String getId() {
            return "LoadState";
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
